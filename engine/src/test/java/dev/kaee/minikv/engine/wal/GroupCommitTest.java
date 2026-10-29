package dev.kaee.minikv.engine.wal;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupCommitTest {

  private static final int THREADS = 32;
  private static final int PER_THREAD = 100;

  @TempDir Path dir;

  @Test
  void alwaysModeSharesFsyncsAcrossConcurrentWriters() throws Exception {
    long fsyncs = runConcurrentWrites(FsyncMode.ALWAYS);
    System.out.printf(
        "ALWAYS: %d records, %d fsyncs, %.1f records per fsync%n",
        THREADS * PER_THREAD, fsyncs, (double) THREADS * PER_THREAD / fsyncs);
    assertThat(fsyncs).isGreaterThan(0L);
    // Writers that arrive during an fsync must share the next one. Before leader/follower group
    // commit this measured about 1.2 records per fsync, so require at least 2.
    assertThat(fsyncs * 2 <= (long) THREADS * PER_THREAD).isTrue();
  }

  @Test
  void batchModeAcknowledgesAfterBackgroundFsync() throws Exception {
    long fsyncs = runConcurrentWrites(FsyncMode.BATCH);
    System.out.printf("BATCH: %d records, %d fsyncs%n", THREADS * PER_THREAD, fsyncs);
    assertThat(fsyncs).isGreaterThan(0L);
    assertThat(fsyncs).isLessThan((long) THREADS * PER_THREAD);
  }

  private long runConcurrentWrites(FsyncMode mode) throws Exception {
    SyncPolicy policy = SyncPolicy.create(mode, Duration.ofMillis(5));
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try (WriteAheadLog wal = WriteAheadLog.open(dir.resolve(mode.name()), policy)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        int thread = t;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < PER_THREAD; i++) {
                    wal.append(WalRecord.put(b(thread + "-" + i), b("value"), i));
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
      assertThat(wal.appendedRecords()).isEqualTo((long) THREADS * PER_THREAD);
      // Count fsyncs before close(), which adds one of its own.
      return wal.fsyncCount();
    } finally {
      pool.shutdownNow();
    }
  }
}
