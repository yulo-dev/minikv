package dev.kaee.minikv.engine;

/**
 * Layout of the 64-bit write timestamps the cluster's hybrid logical clock produces: {@code
 * physicalMillis:48 | logical:8 | nodeId:8}.
 *
 * <p>Ordering timestamps as plain longs orders by physical time, then logical counter, then node
 * id, which is the last-write-wins rule with node id as the tie-breaker. 48 bits of milliseconds
 * last until the year 10889. The engine only needs {@link #physicalMillis(long)}, to age
 * tombstones.
 */
public final class Timestamps {

  public static final int PHYSICAL_SHIFT = 16;
  public static final int MAX_LOGICAL = 0xff;
  public static final int MAX_NODE_ID = 0xff;

  private Timestamps() {}

  public static long of(long physicalMillis, int logical, int nodeId) {
    if (logical < 0 || logical > MAX_LOGICAL || nodeId < 0 || nodeId > MAX_NODE_ID) {
      throw new IllegalArgumentException("logical and nodeId must fit in 8 bits");
    }
    return (physicalMillis << PHYSICAL_SHIFT) | ((long) logical << 8) | nodeId;
  }

  public static long physicalMillis(long timestamp) {
    return timestamp >>> PHYSICAL_SHIFT;
  }
}
