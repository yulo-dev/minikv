package dev.kaee.minikv.engine.wal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background thread behind BATCH mode: one fsync per interval covers every pending writer.
 *
 * <p>Runs at a fixed rate, so fsyncs start every interval rather than an interval after the
 * previous fsync finished. If an fsync takes longer than the interval, the next one starts as soon
 * as it ends; runs never overlap.
 */
final class GroupCommitter {

  private ScheduledExecutorService executor;
  private ScheduledFuture<?> task;

  synchronized void start(WriteAheadLog wal, Duration interval) {
    if (executor != null) {
      throw new IllegalStateException("already started");
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "wal-group-commit");
              t.setDaemon(true);
              return t;
            });
    long micros = interval.toNanos() / 1000;
    task = executor.scheduleAtFixedRate(wal::backgroundSync, micros, micros, TimeUnit.MICROSECONDS);
  }

  synchronized void stop() {
    if (executor == null) {
      return;
    }
    task.cancel(false);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    executor = null;
  }
}
