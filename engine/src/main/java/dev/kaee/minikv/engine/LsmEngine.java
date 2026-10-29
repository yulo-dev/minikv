package dev.kaee.minikv.engine;

import dev.kaee.minikv.engine.compaction.MergeIterator;
import dev.kaee.minikv.engine.manifest.Edit;
import dev.kaee.minikv.engine.manifest.Manifest;
import dev.kaee.minikv.engine.memtable.MemTable;
import dev.kaee.minikv.engine.sstable.SSTableReader;
import dev.kaee.minikv.engine.sstable.SSTableWriter;
import dev.kaee.minikv.engine.wal.FileSync;
import dev.kaee.minikv.engine.wal.RecordType;
import dev.kaee.minikv.engine.wal.SyncPolicy;
import dev.kaee.minikv.engine.wal.WalRecord;
import dev.kaee.minikv.engine.wal.WalReplay;
import dev.kaee.minikv.engine.wal.WriteAheadLog;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LSM engine: WAL, memtables and SSTables, tied together by the manifest.
 *
 * <p>Write path: append to the WAL, wait for durability, insert into the active memtable. At the
 * size threshold the memtable freezes, a new WAL segment starts, and a background thread writes the
 * frozen memtable to an SSTable, records it in the manifest, then deletes the WAL segments it
 * covered. A second background thread merges SSTables as the compaction strategy asks.
 *
 * <p>A write holds the rotation read lock across WAL append, fsync wait and memtable insert, so a
 * record in WAL segment {@code k} is always in the memtable that covers segment {@code k}. That is
 * what makes deleting a segment after its memtable is flushed safe.
 *
 * <p>Read path: an immutable {@link State} snapshot of active memtable, frozen memtables and
 * SSTables, each table reference counted for the duration of the read. Writes may arrive out of
 * timestamp order, so a newer component can hold an older version; every component is consulted,
 * and an SSTable whose max timestamp cannot beat the best version so far is skipped.
 *
 * <p>Neither flush nor compaction blocks writers except for the brief state swap under the rotation
 * write lock. Reads never block: a read that started before a compaction swap keeps reading the old
 * tables until it finishes.
 */
final class LsmEngine implements Engine {

  private static final System.Logger LOG = System.getLogger(LsmEngine.class.getName());
  private static final Pattern SSTABLE_NAME = Pattern.compile("sst-(\\d{20})\\.sst");

  /** A frozen memtable and the last WAL segment whose records it holds. */
  private record Frozen(MemTable memtable, long walSegmentThrough) {}

  /** A live SSTable and its manifest id. */
  private record Table(long id, SSTableReader reader) {}

  /** Immutable snapshot of the read path. Memtable list is newest first. */
  private record State(MemTable active, List<Frozen> immutables, List<Table> tables) {}

  private final EngineConfig config;
  private final WriteAheadLog wal;
  private final Manifest manifest;
  private final ReentrantReadWriteLock rotationLock = new ReentrantReadWriteLock();
  private final Object flushMonitor = new Object();
  private final ExecutorService flusher = daemonExecutor("memtable-flush");
  private final ExecutorService compactor = daemonExecutor("compaction");
  private final long recoveredRecords;
  private final long truncatedWalBytes;
  private final AtomicLong flushes = new AtomicLong();
  private final AtomicLong compactions = new AtomicLong();
  private final AtomicLong tombstonesDropped = new AtomicLong();

  private volatile State state;
  private volatile boolean closed;
  private volatile Exception flushFailure;

  private LsmEngine(
      EngineConfig config,
      WriteAheadLog wal,
      Manifest manifest,
      State initial,
      WalReplay.Result replay) {
    this.config = config;
    this.wal = wal;
    this.manifest = manifest;
    this.state = initial;
    this.recoveredRecords = replay.records();
    this.truncatedWalBytes = replay.truncatedBytes();
  }

  private static ExecutorService daemonExecutor(String name) {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, name);
          t.setDaemon(true);
          return t;
        });
  }

  static LsmEngine open(EngineConfig config) throws IOException {
    Files.createDirectories(config.sstableDir());
    Manifest manifest = Manifest.open(config.dataDir());
    List<Table> tables = new ArrayList<>();
    try {
      Manifest.State ms = manifest.state();
      removeOrphans(config, ms);
      for (int i = ms.liveTables().size() - 1; i >= 0; i--) {
        long id = ms.liveTables().get(i);
        tables.add(new Table(id, SSTableReader.open(sstablePath(config, id))));
      }
      MemTable recovered = new MemTable();
      WalReplay.Result replay =
          WalReplay.replay(config.walDir(), rec -> recovered.apply(rec.key(), toVersion(rec)));
      SyncPolicy policy = SyncPolicy.create(config.fsyncMode(), config.groupCommitInterval());
      WriteAheadLog wal = WriteAheadLog.open(config.walDir(), policy, ms.walRetiredThrough());
      State initial = new State(recovered, List.of(), List.copyOf(tables));
      LsmEngine engine = new LsmEngine(config, wal, manifest, initial, replay);
      engine.scheduleCompaction();
      return engine;
    } catch (IOException | RuntimeException e) {
      for (Table t : tables) {
        t.reader().close();
      }
      manifest.close();
      throw e;
    }
  }

  /**
   * Deletes what a crash can leave behind: temp files, SSTables the manifest does not list (an
   * unrecorded flush or compaction output, or compaction inputs whose deletion was cut short), and
   * WAL segments the manifest already retired.
   */
  private static void removeOrphans(EngineConfig config, Manifest.State ms) throws IOException {
    Set<Long> live = new HashSet<>(ms.liveTables());
    boolean deleted = false;
    try (var files = Files.list(config.sstableDir())) {
      for (Path p : (Iterable<Path>) files::iterator) {
        String name = p.getFileName().toString();
        Matcher m = SSTABLE_NAME.matcher(name);
        boolean orphan = name.endsWith(".tmp") || (m.matches() && !live.contains(parse(m)));
        if (orphan) {
          LOG.log(System.Logger.Level.INFO, "removing orphaned file {0}", p);
          Files.delete(p);
          deleted = true;
        }
      }
    }
    if (deleted) {
      FileSync.fsyncDirectory(config.sstableDir());
    }
    deleted = false;
    for (long id : WriteAheadLog.listSegments(config.walDir())) {
      if (id <= ms.walRetiredThrough()) {
        Files.delete(WriteAheadLog.segmentPath(config.walDir(), id));
        deleted = true;
      }
    }
    if (deleted) {
      FileSync.fsyncDirectory(config.walDir());
    }
  }

  private static long parse(Matcher m) {
    return Long.parseLong(m.group(1));
  }

  static Path sstablePath(EngineConfig config, long id) {
    return config.sstableDir().resolve("sst-" + String.format(Locale.ROOT, "%020d", id) + ".sst");
  }

  private static VersionedValue toVersion(WalRecord rec) {
    return rec.type() == RecordType.DELETE
        ? VersionedValue.tombstoneAt(rec.timestamp())
        : VersionedValue.of(rec.value(), rec.timestamp());
  }

  // ---- writes ----

  @Override
  public void put(byte[] key, byte[] value, long timestamp) {
    Bytes.checkKey(key);
    Bytes.checkValue(value);
    write(WalRecord.put(key.clone(), value.clone(), timestamp));
  }

  @Override
  public void delete(byte[] key, long timestamp) {
    Bytes.checkKey(key);
    write(WalRecord.delete(key.clone(), timestamp));
  }

  private void write(WalRecord rec) {
    ensureOpen();
    awaitFlushCapacity();
    rotationLock.readLock().lock();
    try {
      ensureOpen();
      wal.append(rec);
      state.active().apply(rec.key(), toVersion(rec));
    } catch (IOException e) {
      throw new UncheckedIOException("WAL append failed", e);
    } finally {
      rotationLock.readLock().unlock();
    }
    maybeRotate();
  }

  // Back-pressure: stall writers while too many frozen memtables wait for flush.
  private void awaitFlushCapacity() {
    awaitFlushes(() -> state.immutables().size() < config.maxImmutableMemtables());
  }

  private void maybeRotate() {
    if (state.active().approximateBytes() < config.memtableFlushBytes()) {
      return;
    }
    if (!rotationLock.writeLock().tryLock()) {
      return; // another writer is rotating
    }
    try {
      if (closed || state.active().approximateBytes() < config.memtableFlushBytes()) {
        return;
      }
      rotateLocked();
    } catch (IOException e) {
      throw new UncheckedIOException("WAL rotation failed", e);
    } finally {
      rotationLock.writeLock().unlock();
    }
    scheduleFlush();
  }

  // Caller holds the rotation write lock.
  private void rotateLocked() throws IOException {
    State s = state;
    long closedSegment = wal.rotate();
    s.active().freeze();
    List<Frozen> immutables = new ArrayList<>(s.immutables().size() + 1);
    immutables.add(new Frozen(s.active(), closedSegment));
    immutables.addAll(s.immutables());
    state = new State(new MemTable(), List.copyOf(immutables), s.tables());
  }

  private void scheduleFlush() {
    try {
      flusher.execute(this::flushPending);
    } catch (RejectedExecutionException e) {
      // Engine is closing; frozen memtables stay covered by the WAL.
    }
  }

  // ---- flush (flusher thread) ----

  private void flushPending() {
    try {
      while (!closed) {
        List<Frozen> pending = state.immutables();
        if (pending.isEmpty()) {
          return;
        }
        flushOne(pending.get(pending.size() - 1)); // oldest first keeps WAL retirement in order
      }
    } catch (IOException | RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "memtable flush failed", e);
      synchronized (flushMonitor) {
        flushFailure = e;
        flushMonitor.notifyAll();
      }
    }
  }

  private void flushOne(Frozen frozen) throws IOException {
    long id = manifest.nextFileId();
    Path path = sstablePath(config, id);
    try (SSTableWriter w = newWriter(path)) {
      for (Map.Entry<byte[], VersionedValue> e : frozen.memtable().entries()) {
        w.add(e.getKey(), e.getValue());
      }
      w.finish();
    }
    SSTableReader reader = SSTableReader.open(path);
    // Durable before acting: from here on, recovery trusts the table and skips the WAL segments.
    manifest.apply(Edit.flush(id, frozen.walSegmentThrough()));

    rotationLock.writeLock().lock();
    try {
      State s = state;
      List<Frozen> immutables = new ArrayList<>(s.immutables());
      immutables.removeIf(frozen::equals);
      List<Table> tables = new ArrayList<>(s.tables().size() + 1);
      tables.add(new Table(id, reader));
      tables.addAll(s.tables());
      state = new State(s.active(), List.copyOf(immutables), List.copyOf(tables));
    } finally {
      rotationLock.writeLock().unlock();
    }
    wal.deleteSegmentsThrough(frozen.walSegmentThrough());
    flushes.incrementAndGet();
    synchronized (flushMonitor) {
      flushMonitor.notifyAll();
    }
    scheduleCompaction();
  }

  private SSTableWriter newWriter(Path path) throws IOException {
    return SSTableWriter.builder(path)
        .blockBytes(config.sstableBlockBytes())
        .bitsPerKey(config.bloomBitsPerKey())
        .open();
  }

  /** Freezes the active memtable if it has data and waits until every memtable is in SSTables. */
  @Override
  public void flush() {
    ensureOpen();
    checkFlushFailure();
    rotationLock.writeLock().lock();
    try {
      if (state.active().size() > 0) {
        rotateLocked();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("WAL rotation failed", e);
    } finally {
      rotationLock.writeLock().unlock();
    }
    scheduleFlush();
    awaitFlushes(() -> state.immutables().isEmpty());
  }

  /** Blocks until {@code done} holds, rechecking after every flush. */
  private void awaitFlushes(BooleanSupplier done) {
    synchronized (flushMonitor) {
      while (!done.getAsBoolean()) {
        checkFlushFailure();
        ensureOpen();
        try {
          flushMonitor.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new UncheckedIOException(
              new InterruptedIOException("interrupted waiting for flush"));
        }
      }
    }
  }

  private void checkFlushFailure() {
    Exception failure = flushFailure;
    if (failure != null) {
      throw new IllegalStateException("background flush failed; engine is read-only", failure);
    }
  }

  // ---- compaction (compactor thread) ----

  private void scheduleCompaction() {
    if (!config.autoCompaction()) {
      return;
    }
    try {
      compactor.execute(this::compactWhileNeeded);
    } catch (RejectedExecutionException e) {
      // Engine is closing.
    }
  }

  private void compactWhileNeeded() {
    try {
      while (!closed) {
        List<Table> picked =
            config.compactionStrategy().select(state.tables(), t -> t.reader().fileBytes());
        if (picked.isEmpty()) {
          return;
        }
        compactTables(picked);
      }
    } catch (IOException | RuntimeException e) {
      // Not fatal: the inputs stay live and correct. The next flush retries.
      LOG.log(System.Logger.Level.ERROR, "compaction failed", e);
    }
  }

  @Override
  public void compact() {
    flush();
    Future<?> done =
        compactor.submit(
            () -> {
              List<Table> all = state.tables();
              if (!all.isEmpty()) {
                compactTables(all);
              }
              return null;
            });
    try {
      done.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new InterruptedIOException("interrupted during compaction"));
    } catch (ExecutionException e) {
      throw new IllegalStateException("compaction failed", e.getCause());
    }
  }

  /**
   * Merges {@code inputs} into at most one new table. Runs only on the compactor thread, which is
   * the only code that removes tables, so the inputs stay live throughout.
   */
  private void compactTables(List<Table> inputs) throws IOException {
    Set<Long> inputIds = new HashSet<>();
    List<Iterator<Entry>> sources = new ArrayList<>();
    for (Table t : inputs) {
      inputIds.add(t.id());
      sources.add(t.reader().iterator(null, null));
    }
    long id = manifest.nextFileId();
    Path path = sstablePath(config, id);
    boolean wroteAny = false;
    long dropped = 0;
    try (SSTableWriter w = newWriter(path)) {
      MergeIterator merged = new MergeIterator(sources);
      while (merged.hasNext()) {
        Entry e = merged.next();
        if (e.value().tombstone() && canDropTombstone(e, inputIds)) {
          dropped++;
          continue;
        }
        w.add(e.key(), e.value());
        wroteAny = true;
      }
      if (wroteAny) {
        w.finish();
      }
    }
    SSTableReader output = wroteAny ? SSTableReader.open(path) : null;
    List<Long> added = wroteAny ? List.of(id) : List.of();
    manifest.apply(new Edit(added, List.copyOf(inputIds), 0));

    rotationLock.writeLock().lock();
    try {
      State s = state;
      List<Table> tables = new ArrayList<>(s.tables().size());
      for (Table t : s.tables()) {
        if (!inputIds.contains(t.id())) {
          tables.add(t);
        }
      }
      if (output != null) {
        tables.add(new Table(id, output)); // holds the oldest data, so it goes last
      }
      state = new State(s.active(), s.immutables(), List.copyOf(tables));
    } finally {
      rotationLock.writeLock().unlock();
    }
    for (Table t : inputs) {
      t.reader().deleteFile();
      t.reader().release();
    }
    FileSync.fsyncDirectory(config.sstableDir());
    compactions.incrementAndGet();
    tombstonesDropped.addAndGet(dropped);
  }

  /**
   * A tombstone may go only if it is past the grace period and no other component could hold an
   * older version of the key. The spec's rule ("the compaction includes the oldest table") assumes
   * older tables hold older timestamps; here writes arrive out of timestamp order, so any table or
   * memtable outside the compaction is checked instead. That is strictly safer.
   */
  private boolean canDropTombstone(Entry e, Set<Long> inputIds) throws IOException {
    if (!config.tombstonePolicy().expired(e.value().timestamp())) {
      return false;
    }
    State s = state;
    if (s.active().get(e.key()).isPresent()) {
      return false;
    }
    for (Frozen f : s.immutables()) {
      if (f.memtable().get(e.key()).isPresent()) {
        return false;
      }
    }
    for (Table t : s.tables()) {
      if (!inputIds.contains(t.id()) && t.reader().get(e.key()).isPresent()) {
        return false;
      }
    }
    return true;
  }

  // ---- reads ----

  /** Snapshot whose tables are all referenced; release with {@link #release(State)}. */
  private State acquire() {
    while (true) {
      ensureOpen();
      State s = state;
      List<Table> held = new ArrayList<>(s.tables().size());
      boolean ok = true;
      for (Table t : s.tables()) {
        if (t.reader().tryAcquire()) {
          held.add(t);
        } else {
          ok = false; // replaced by a compaction since we read state; take a fresh snapshot
          break;
        }
      }
      if (ok) {
        return s;
      }
      held.forEach(t -> t.reader().release());
    }
  }

  private static void release(State s) {
    s.tables().forEach(t -> t.reader().release());
  }

  @Override
  public Optional<VersionedValue> get(byte[] key) {
    Bytes.checkKey(key);
    State s = acquire();
    try {
      VersionedValue best = newer(null, s.active().get(key).orElse(null));
      for (Frozen f : s.immutables()) {
        best = newer(best, f.memtable().get(key).orElse(null));
      }
      for (Table t : s.tables()) {
        if (best != null && t.reader().maxTimestamp() <= best.timestamp()) {
          continue; // nothing in this table can win
        }
        best = newer(best, t.reader().get(key).orElse(null));
      }
      return Optional.ofNullable(best);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      release(s);
    }
  }

  private static VersionedValue newer(VersionedValue best, VersionedValue candidate) {
    if (candidate == null) {
      return best;
    }
    return best == null ? candidate : VersionedValue.newer(best, candidate);
  }

  /**
   * Streams live entries through a k-way merge of every memtable and table. The snapshot's tables
   * stay referenced until the iterator is exhausted, so consume it fully; an abandoned iterator
   * holds its files open until the engine closes.
   */
  @Override
  public Iterator<Entry> scan(byte[] fromInclusive, byte[] toExclusive) {
    State s = acquire();
    List<Iterator<Entry>> sources = new ArrayList<>();
    sources.add(memtableIterator(s.active(), fromInclusive, toExclusive));
    for (Frozen f : s.immutables()) {
      sources.add(memtableIterator(f.memtable(), fromInclusive, toExclusive));
    }
    for (Table t : s.tables()) {
      sources.add(t.reader().iterator(fromInclusive, toExclusive));
    }
    MergeIterator merged = new MergeIterator(sources);
    return new Iterator<>() {
      private Entry next = advance();

      private Entry advance() {
        while (merged.hasNext()) {
          Entry e = merged.next();
          if (!e.value().tombstone()) {
            return e;
          }
        }
        release(s);
        return null;
      }

      @Override
      public boolean hasNext() {
        return next != null;
      }

      @Override
      public Entry next() {
        if (next == null) {
          throw new NoSuchElementException();
        }
        Entry current = next;
        next = advance();
        return current;
      }
    };
  }

  private static Iterator<Entry> memtableIterator(MemTable m, byte[] from, byte[] to) {
    Iterator<Map.Entry<byte[], VersionedValue>> it = m.range(from, to).entrySet().iterator();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public Entry next() {
        Map.Entry<byte[], VersionedValue> e = it.next();
        return new Entry(e.getKey(), e.getValue());
      }
    };
  }

  @Override
  public EngineStats stats() {
    State s = state;
    long negatives = 0;
    long falsePositives = 0;
    for (Table t : s.tables()) {
      negatives += t.reader().bloomNegatives();
      falsePositives += t.reader().bloomFalsePositives();
    }
    return new EngineStats(
        s.active().approximateBytes(),
        s.immutables().size(),
        s.tables().size(),
        wal.liveSegments().size(),
        wal.fsyncCount(),
        recoveredRecords,
        truncatedWalBytes,
        flushes.get(),
        compactions.get(),
        tombstonesDropped.get(),
        negatives,
        falsePositives);
  }

  /** Stops background work and closes files. Unflushed memtables stay recoverable from the WAL. */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    flusher.shutdown();
    compactor.shutdown();
    try {
      if (!flusher.awaitTermination(1, TimeUnit.MINUTES)
          || !compactor.awaitTermination(1, TimeUnit.MINUTES)) {
        LOG.log(System.Logger.Level.WARNING, "background work did not finish within 1 minute");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    synchronized (flushMonitor) {
      flushMonitor.notifyAll();
    }
    rotationLock.writeLock().lock();
    try {
      wal.close();
      for (Table t : state.tables()) {
        t.reader().close();
      }
      manifest.close();
    } catch (IOException e) {
      throw new UncheckedIOException("close failed", e);
    } finally {
      rotationLock.writeLock().unlock();
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("engine is closed");
    }
  }
}
