package dev.kaee.minikv.engine;

import java.util.Iterator;
import java.util.Optional;

/**
 * Single-node key-value storage engine.
 *
 * <p>Writes are durable according to the configured fsync mode when the call returns. For one key
 * the version with the highest timestamp wins, whatever order writes arrive in.
 */
public interface Engine extends AutoCloseable {

  void put(byte[] key, byte[] value, long timestamp);

  /** Writes a tombstone. */
  void delete(byte[] key, long timestamp);

  /** Newest version of the key, including tombstones, so callers can compare versions. */
  Optional<VersionedValue> get(byte[] key);

  /** Live entries in {@code [fromInclusive, toExclusive)}; a null bound means unbounded. */
  Iterator<Entry> scan(byte[] fromInclusive, byte[] toExclusive);

  void flush();

  EngineStats stats();

  @Override
  void close();
}
