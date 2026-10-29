package dev.kaee.minikv.engine;

/**
 * A value with its write timestamp. A tombstone has an empty value and {@code tombstone == true}.
 */
public record VersionedValue(byte[] value, long timestamp, boolean tombstone) {

  private static final byte[] EMPTY = new byte[0];

  public static VersionedValue of(byte[] value, long timestamp) {
    return new VersionedValue(value, timestamp, false);
  }

  public static VersionedValue tombstoneAt(long timestamp) {
    return new VersionedValue(EMPTY, timestamp, true);
  }

  // Last-write-wins. Timestamps carry a node-id suffix, so two different writes never share one;
  // an equal timestamp means the same write seen twice, and keeping the existing copy is correct.
  public boolean supersedes(VersionedValue other) {
    return timestamp > other.timestamp;
  }

  public static VersionedValue newer(VersionedValue a, VersionedValue b) {
    return b.supersedes(a) ? b : a;
  }
}
