package dev.kaee.minikv.engine.compaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Size-tiered compaction: group tables of similar size and merge a group once it has {@code
 * minThreshold} members.
 *
 * <p>A table joins a bucket when its size is within {@code [0.5, 1.5]} times the bucket average.
 * Tables under {@code smallTableBytes} all share one bucket, so freshly flushed tables, whose sizes
 * vary with key and value lengths, still get merged. Among eligible buckets the one with the
 * smallest average wins: merging small tables is cheap and cuts the table count fastest. At most
 * {@code maxThreshold} tables are merged at once.
 *
 * <p>Trade-off against leveled compaction: less write amplification, but more space amplification
 * and more tables per read, which bloom filters and max-timestamp pruning offset.
 */
public final class SizeTieredStrategy implements CompactionStrategy {

  private static final double BUCKET_LOW = 0.5;
  private static final double BUCKET_HIGH = 1.5;

  private final int minThreshold;
  private final int maxThreshold;
  private final long smallTableBytes;

  public SizeTieredStrategy(int minThreshold, int maxThreshold, long smallTableBytes) {
    if (minThreshold < 2 || maxThreshold < minThreshold) {
      throw new IllegalArgumentException("need 2 <= minThreshold <= maxThreshold");
    }
    this.minThreshold = minThreshold;
    this.maxThreshold = maxThreshold;
    this.smallTableBytes = smallTableBytes;
  }

  private static final class Bucket<T> {
    final List<T> tables = new ArrayList<>();
    long totalBytes;

    double average() {
      return (double) totalBytes / tables.size();
    }
  }

  @Override
  public <T> List<T> select(List<T> tables, ToLongFunction<T> sizeBytes) {
    List<T> sorted = new ArrayList<>(tables);
    sorted.sort(Comparator.comparingLong(sizeBytes));
    List<Bucket<T>> buckets = new ArrayList<>();
    for (T t : sorted) {
      long size = sizeBytes.applyAsLong(t);
      Bucket<T> home = null;
      for (Bucket<T> b : buckets) {
        double avg = b.average();
        boolean similar = size >= avg * BUCKET_LOW && size <= avg * BUCKET_HIGH;
        boolean bothSmall = size < smallTableBytes && avg < smallTableBytes;
        if (similar || bothSmall) {
          home = b;
          break;
        }
      }
      if (home == null) {
        home = new Bucket<>();
        buckets.add(home);
      }
      home.tables.add(t);
      home.totalBytes += size;
    }
    Bucket<T> best = null;
    for (Bucket<T> b : buckets) {
      if (b.tables.size() >= minThreshold && (best == null || b.average() < best.average())) {
        best = b;
      }
    }
    if (best == null) {
      return List.of();
    }
    return List.copyOf(best.tables.subList(0, Math.min(maxThreshold, best.tables.size())));
  }
}
