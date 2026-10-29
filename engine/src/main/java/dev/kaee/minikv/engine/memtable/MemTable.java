package dev.kaee.minikv.engine.memtable;

import dev.kaee.minikv.engine.Bytes;
import dev.kaee.minikv.engine.VersionedValue;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sorted buffer of recent writes. Keeps only the newest version of each key.
 *
 * <p>{@link #approximateBytes()} counts every applied write, including ones that replaced an older
 * version. It therefore tracks how much WAL the memtable covers, which is what the flush threshold
 * should bound, and slightly overstates heap use under heavy overwrites.
 */
public final class MemTable {

  static final int ENTRY_OVERHEAD_BYTES = 48;

  private final ConcurrentSkipListMap<byte[], VersionedValue> map =
      new ConcurrentSkipListMap<>(Bytes.UNSIGNED);
  private final AtomicLong bytes = new AtomicLong();
  private volatile boolean frozen;

  public void apply(byte[] key, VersionedValue version) {
    if (frozen) {
      throw new IllegalStateException("memtable is immutable");
    }
    map.merge(key, version, VersionedValue::newer);
    bytes.addAndGet(key.length + version.value().length + ENTRY_OVERHEAD_BYTES);
  }

  public Optional<VersionedValue> get(byte[] key) {
    return Optional.ofNullable(map.get(key));
  }

  /** Read-only view of {@code [from, to)}; a null bound means unbounded. */
  public NavigableMap<byte[], VersionedValue> range(byte[] from, byte[] to) {
    NavigableMap<byte[], VersionedValue> view = map;
    if (from != null) {
      view = view.tailMap(from, true);
    }
    if (to != null) {
      view = view.headMap(to, false);
    }
    return Collections.unmodifiableNavigableMap(view);
  }

  public Iterable<Map.Entry<byte[], VersionedValue>> entries() {
    return range(null, null).entrySet();
  }

  /** Marks the memtable read-only. Called under the engine's rotation lock. */
  public void freeze() {
    frozen = true;
  }

  public boolean isFrozen() {
    return frozen;
  }

  public long approximateBytes() {
    return bytes.get();
  }

  public int size() {
    return map.size();
  }
}
