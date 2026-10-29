package dev.kaee.minikv.engine;

import java.time.Clock;
import java.time.Duration;

/**
 * Decides when a tombstone is old enough for compaction to drop.
 *
 * <p>Age alone is not enough: compaction also keeps any tombstone whose key still exists in a
 * memtable or in a table outside the compaction, since dropping it would resurrect that older
 * value. The grace period covers what the engine cannot see: replicas that missed the delete and
 * must receive it through read repair or hints before it disappears.
 */
@FunctionalInterface
public interface TombstonePolicy {

  Duration DEFAULT_GC_GRACE = Duration.ofMinutes(10);

  boolean expired(long tombstoneTimestamp);

  /** Expired once its physical time is more than {@code grace} before now. */
  static TombstonePolicy gcGrace(Duration grace, Clock clock) {
    long graceMillis = grace.toMillis();
    return ts -> Timestamps.physicalMillis(ts) < clock.millis() - graceMillis;
  }

  static TombstonePolicy never() {
    return ts -> false;
  }
}
