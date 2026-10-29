package dev.kaee.minikv.engine;

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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * covered.
 *
 * <p>A write holds the rotation read lock across WAL append, fsync wait and memtable insert, so a
 * record in WAL segment {@code k} is always in the memtable that covers segment {@code k}. That is
 * what makes deleting a segment after its memtable is flushed safe.
 *
 * <p>Read path: active memtable, frozen memtables, then SSTables, newest first. Writes may arrive
 * out of timestamp order, so a newer component can hold an older version; every component is
 * consulted, and an SSTable whose max timestamp cannot beat the best version so far is skipped.
 */
final class LsmEngine implements Engine {

  private static final System.Logger LOG = System.getLogger(LsmEngine.class.getName());
  private static final Pattern SSTABLE_NAME = Pattern.compile("sst-(\\d{20})\\.sst");

  /** A frozen memtable and the last WAL segment whose records it holds. */
  private record Frozen(MemTable memtable, long walSegmentThrough) {}

  /** Immutable snapshot of the read path. Lists are newest first. */
  private record State(MemTable active, List<Frozen> immutables, List<SSTableReader> tables) {}

  private final EngineConfig config;
  private final WriteAheadLog wal;
  private final Manifest manifest;
  private final ReentrantReadWriteLock rotationLock = new ReentrantReadWriteLock();
  private final Object flushMonitor = new Object();
  private final ExecutorService flusher;
  private final long recoveredRecords;
  private final long truncatedWalBytes;
  private final AtomicLong flushes = new AtomicLong();

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
    this.flusher =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "memtable-flush");
              t.setDaemon(true);
              return t;
            });
  }

  static LsmEngine open(EngineConfig config) throws IOException {
    Files.createDirectories(config.sstableDir());
    Manifest manifest = Manifest.open(config.dataDir());
    List<SSTableReader> tables = new ArrayList<>();
    try {
      Manifest.State ms = manifest.state();
      removeOrphans(config, ms);
      for (int i = ms.liveTables().size() - 1; i >= 0; i--) {
        tables.add(SSTableReader.open(sstablePath(config, ms.liveTables().get(i))));
      }
      MemTable recovered = new MemTable();
      WalReplay.Result replay =
          WalReplay.replay(config.walDir(), rec -> recovered.apply(rec.key(), toVersion(rec)));
      SyncPolicy policy = SyncPolicy.create(config.fsyncMode(), config.groupCommitInterval());
      WriteAheadLog wal = WriteAheadLog.open(config.walDir(), policy, ms.walRetiredThrough());
      State initial = new State(recovered, List.of(), List.copyOf(tables));
      return new LsmEngine(config, wal, manifest, initial, replay);
    } catch (IOException | RuntimeException e) {
      for (SSTableReader t : tables) {
        t.close();
      }
      manifest.close();
      throw e;
    }
  }

  /**
   * Deletes what a crash can leave behind: temp files, SSTables the manifest never recorded, and
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

  // ---- flush (background thread) ----

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
    try (SSTableWriter w =
        SSTableWriter.builder(path)
            .blockBytes(config.sstableBlockBytes())
            .bitsPerKey(config.bloomBitsPerKey())
            .open()) {
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
      List<SSTableReader> tables = new ArrayList<>(s.tables().size() + 1);
      tables.add(reader);
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

  // ---- reads ----

  @Override
  public Optional<VersionedValue> get(byte[] key) {
    Bytes.checkKey(key);
    ensureOpen();
    State s = state;
    VersionedValue best = newer(null, s.active().get(key).orElse(null));
    for (Frozen f : s.immutables()) {
      best = newer(best, f.memtable().get(key).orElse(null));
    }
    try {
      for (SSTableReader t : s.tables()) {
        if (best != null && t.maxTimestamp() <= best.timestamp()) {
          continue; // nothing in this table can win
        }
        best = newer(best, t.get(key).orElse(null));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Optional.ofNullable(best);
  }

  private static VersionedValue newer(VersionedValue best, VersionedValue candidate) {
    if (candidate == null) {
      return best;
    }
    return best == null ? candidate : VersionedValue.newer(best, candidate);
  }

  @Override
  public Iterator<Entry> scan(byte[] fromInclusive, byte[] toExclusive) {
    ensureOpen();
    State s = state;
    // Materialised merge. The streaming k-way merge iterator replaces this with compaction.
    TreeMap<byte[], VersionedValue> merged = new TreeMap<>(Bytes.UNSIGNED);
    List<MemTable> memtables = new ArrayList<>();
    memtables.add(s.active());
    s.immutables().forEach(f -> memtables.add(f.memtable()));
    for (MemTable m : memtables) {
      for (Map.Entry<byte[], VersionedValue> e : m.range(fromInclusive, toExclusive).entrySet()) {
        merged.merge(e.getKey(), e.getValue(), VersionedValue::newer);
      }
    }
    for (SSTableReader t : s.tables()) {
      t.iterator(fromInclusive, toExclusive)
          .forEachRemaining(e -> merged.merge(e.key(), e.value(), VersionedValue::newer));
    }
    List<Entry> live = new ArrayList<>(merged.size());
    for (Map.Entry<byte[], VersionedValue> e : merged.entrySet()) {
      if (!e.getValue().tombstone()) {
        live.add(new Entry(e.getKey(), e.getValue()));
      }
    }
    return live.iterator();
  }

  @Override
  public EngineStats stats() {
    State s = state;
    long negatives = 0;
    long falsePositives = 0;
    for (SSTableReader t : s.tables()) {
      negatives += t.bloomNegatives();
      falsePositives += t.bloomFalsePositives();
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
    try {
      if (!flusher.awaitTermination(1, TimeUnit.MINUTES)) {
        LOG.log(System.Logger.Level.WARNING, "flush did not finish within 1 minute of close");
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
      for (SSTableReader t : state.tables()) {
        t.close();
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
