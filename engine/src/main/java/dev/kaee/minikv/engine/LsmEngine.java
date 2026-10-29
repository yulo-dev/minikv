package dev.kaee.minikv.engine;

import dev.kaee.minikv.engine.memtable.MemTable;
import dev.kaee.minikv.engine.wal.RecordType;
import dev.kaee.minikv.engine.wal.SyncPolicy;
import dev.kaee.minikv.engine.wal.WalRecord;
import dev.kaee.minikv.engine.wal.WalReplay;
import dev.kaee.minikv.engine.wal.WriteAheadLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Engine built from a WAL and memtables. SSTables, flush and compaction arrive in later
 * milestones; until then immutable memtables stay in memory and their WAL segments stay on disk.
 *
 * <p>A write holds the rotation read lock across WAL append, fsync wait and memtable insert. This
 * keeps the invariant later milestones depend on: a record in WAL segment {@code k} is in the
 * memtable that segment {@code k} covers, so a segment can be deleted once that memtable is
 * flushed.
 */
final class LsmEngine implements Engine {

  private final EngineConfig config;
  private final WriteAheadLog wal;
  private final ReentrantReadWriteLock rotationLock = new ReentrantReadWriteLock();
  private final long recoveredRecords;
  private final long truncatedWalBytes;

  private volatile MemTable active;
  private volatile List<MemTable> immutables = List.of(); // newest first
  private volatile boolean closed;

  private LsmEngine(
      EngineConfig config, WriteAheadLog wal, MemTable recovered, WalReplay.Result r) {
    this.config = config;
    this.wal = wal;
    this.active = recovered;
    this.recoveredRecords = r.records();
    this.truncatedWalBytes = r.truncatedBytes();
  }

  static LsmEngine open(EngineConfig config) throws IOException {
    MemTable recovered = new MemTable();
    WalReplay.Result result =
        WalReplay.replay(config.walDir(), rec -> recovered.apply(rec.key(), toVersion(rec)));
    SyncPolicy policy = SyncPolicy.create(config.fsyncMode(), config.groupCommitInterval());
    WriteAheadLog wal = WriteAheadLog.open(config.walDir(), policy);
    return new LsmEngine(config, wal, recovered, result);
  }

  private static VersionedValue toVersion(WalRecord rec) {
    return rec.type() == RecordType.DELETE
        ? VersionedValue.tombstoneAt(rec.timestamp())
        : VersionedValue.of(rec.value(), rec.timestamp());
  }

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
    rotationLock.readLock().lock();
    try {
      wal.append(rec);
      active.apply(rec.key(), toVersion(rec));
    } catch (IOException e) {
      throw new UncheckedIOException("WAL append failed", e);
    } finally {
      rotationLock.readLock().unlock();
    }
    maybeRotate();
  }

  private void maybeRotate() {
    if (active.approximateBytes() < config.memtableFlushBytes()) {
      return;
    }
    if (!rotationLock.writeLock().tryLock()) {
      return; // another writer is rotating
    }
    try {
      MemTable full = active;
      if (full.approximateBytes() < config.memtableFlushBytes()) {
        return;
      }
      wal.rotate();
      full.freeze();
      List<MemTable> next = new ArrayList<>(immutables.size() + 1);
      next.add(full);
      next.addAll(immutables);
      immutables = List.copyOf(next);
      active = new MemTable();
    } catch (IOException e) {
      throw new UncheckedIOException("WAL rotation failed", e);
    } finally {
      rotationLock.writeLock().unlock();
    }
  }

  @Override
  public Optional<VersionedValue> get(byte[] key) {
    Bytes.checkKey(key);
    ensureOpen();
    // Each memtable keeps the newest version it saw, and a newer memtable can still hold an older
    // timestamp (writes arrive out of order), so compare across all of them.
    VersionedValue best = null;
    for (MemTable m : snapshot()) {
      VersionedValue v = m.get(key).orElse(null);
      if (v != null && (best == null || v.supersedes(best))) {
        best = v;
      }
    }
    return Optional.ofNullable(best);
  }

  @Override
  public Iterator<Entry> scan(byte[] fromInclusive, byte[] toExclusive) {
    ensureOpen();
    // Materialised merge. The streaming k-way merge iterator replaces this with SSTables.
    TreeMap<byte[], VersionedValue> merged = new TreeMap<>(Bytes.UNSIGNED);
    for (MemTable m : snapshot()) {
      for (Map.Entry<byte[], VersionedValue> e : m.range(fromInclusive, toExclusive).entrySet()) {
        merged.merge(e.getKey(), e.getValue(), VersionedValue::newer);
      }
    }
    List<Entry> live = new ArrayList<>(merged.size());
    for (Map.Entry<byte[], VersionedValue> e : merged.entrySet()) {
      if (!e.getValue().tombstone()) {
        live.add(new Entry(e.getKey(), e.getValue()));
      }
    }
    return live.iterator();
  }

  private List<MemTable> snapshot() {
    rotationLock.readLock().lock();
    try {
      List<MemTable> all = new ArrayList<>(immutables.size() + 1);
      all.add(active);
      all.addAll(immutables);
      return all;
    } finally {
      rotationLock.readLock().unlock();
    }
  }

  @Override
  public void flush() {
    throw new UnsupportedOperationException("flush to SSTable is not implemented yet");
  }

  @Override
  public EngineStats stats() {
    return new EngineStats(
        active.approximateBytes(),
        immutables.size(),
        wal.liveSegments().size(),
        wal.fsyncCount(),
        recoveredRecords,
        truncatedWalBytes);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    rotationLock.writeLock().lock();
    try {
      closed = true;
      wal.close();
    } catch (IOException e) {
      throw new UncheckedIOException("WAL close failed", e);
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
