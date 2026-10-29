package dev.kaee.minikv.engine;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;

import dev.kaee.minikv.engine.compaction.CompactionStrategy;
import dev.kaee.minikv.engine.wal.FsyncMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactionTest {

  @TempDir Path dir;

  private static long now() {
    return Timestamps.of(System.currentTimeMillis(), 0, 1);
  }

  private static long hourAgo() {
    return Timestamps.of(System.currentTimeMillis() - 3_600_000, 0, 1);
  }

  private EngineConfig.Builder config() {
    return EngineConfig.builder(dir)
        .fsyncMode(FsyncMode.NONE)
        .memtableFlushBytes(2048)
        .sstableBlockBytes(256);
  }

  @Test
  void backgroundCompactionBoundsTableCountAndKeepsData() throws Exception {
    try (Engine e = Engines.open(config().build())) {
      for (int i = 0; i < 5000; i++) {
        e.put(b("k" + (i % 700)), b("v" + i), i + 1);
      }
      e.flush();
      waitFor(() -> e.stats().compactions() > 0);
      EngineStats st = e.stats();
      // Size-tiered keeps a few tiers of under 4 tables each, far fewer than one per flush.
      System.out.printf(
          "tables=%d flushes=%d compactions=%d%n", st.sstables(), st.flushes(), st.compactions());
      assertThat(st.sstables() * 10L < st.flushes()).isTrue();
      for (int k = 0; k < 700; k++) {
        assertThat(e.get(b("k" + k)).orElseThrow().value())
            .isEqualTo(b("v" + lastWrite(k, 700, 5000)));
      }
    }
  }

  @Test
  void expiredTombstonesAreDroppedOnFullCompaction() {
    try (Engine e = Engines.open(config().autoCompaction(false).build())) {
      long ts = hourAgo();
      e.put(b("gone"), b("v"), ts - 1);
      e.delete(b("gone"), ts);
      e.put(b("kept"), b("v"), now());
      e.compact();
      assertThat(e.get(b("gone")).isPresent()).isFalse();
      assertThat(e.get(b("kept")).isPresent()).isTrue();
      assertThat(e.stats().tombstonesDropped()).isEqualTo(1L);
      assertThat(e.stats().sstables()).isEqualTo(1);
    }
  }

  @Test
  void tombstonesWithinGracePeriodSurvive() {
    try (Engine e = Engines.open(config().autoCompaction(false).build())) {
      long ts = now();
      e.put(b("k"), b("v"), ts - 1);
      e.delete(b("k"), ts);
      e.compact();
      assertThat(e.get(b("k")).orElseThrow().tombstone()).isTrue();
      assertThat(e.stats().tombstonesDropped()).isEqualTo(0L);
    }
  }

  /** Compacts only the oldest table, once, as soon as at least {@code minTables} exist. */
  private static CompactionStrategy oldestTableOnce(int minTables) {
    AtomicBoolean fired = new AtomicBoolean();
    return new CompactionStrategy() {
      @Override
      public <T> List<T> select(List<T> tables, ToLongFunction<T> sizeBytes) {
        if (tables.size() < minTables || !fired.compareAndSet(false, true)) {
          return List.of();
        }
        return List.of(tables.get(tables.size() - 1));
      }
    };
  }

  @Test
  void tombstoneShadowingAnOlderValueInAnotherTableSurvives() throws Exception {
    EngineConfig cfg =
        config().tombstoneGcGrace(Duration.ZERO).compactionStrategy(oldestTableOnce(2)).build();
    try (Engine e = Engines.open(cfg)) {
      long ts = hourAgo();
      e.delete(b("k"), ts);
      e.flush();
      // An older version arriving late lands in a newer table, outside the next compaction.
      e.put(b("k"), b("older"), ts - 1);
      e.flush();
      waitFor(() -> e.stats().compactions() == 1);
      assertThat(e.stats().tombstonesDropped()).isEqualTo(0L);
      assertThat(e.get(b("k")).orElseThrow().tombstone()).isTrue();

      // Once both versions meet in one merge, the tombstone wins and can then be dropped.
      e.compact();
      assertThat(e.get(b("k")).isPresent()).isFalse();
      assertThat(e.stats().tombstonesDropped()).isEqualTo(1L);
    }
  }

  @Test
  void tombstoneShadowingAnOlderValueInTheMemtableSurvives() throws Exception {
    EngineConfig plain = config().autoCompaction(false).build();
    try (Engine e = Engines.open(plain)) {
      long ts = hourAgo();
      e.delete(b("k"), ts);
      e.flush();
      e.put(b("k"), b("older"), ts - 1); // stays in the WAL, not flushed
    }
    // Reopen: the older value is replayed into the memtable, then the table holding the tombstone
    // is compacted on its own.
    EngineConfig cfg =
        config().tombstoneGcGrace(Duration.ZERO).compactionStrategy(oldestTableOnce(1)).build();
    try (Engine e = Engines.open(cfg)) {
      waitFor(() -> e.stats().compactions() == 1);
      assertThat(e.stats().tombstonesDropped()).isEqualTo(0L);
      assertThat(e.get(b("k")).orElseThrow().tombstone()).isTrue();
    }
  }

  @Test
  void readsStayCorrectWhileCompactionRuns() throws Exception {
    int keys = 300;
    try (Engine e = Engines.open(config().memtableFlushBytes(1024).build())) {
      AtomicLong clock = new AtomicLong(1);
      for (int k = 0; k < keys; k++) {
        e.put(b("k" + k), b("0"), clock.getAndIncrement());
      }
      AtomicBoolean stop = new AtomicBoolean();
      ExecutorService pool = Executors.newFixedThreadPool(4);
      List<Future<?>> readers = new ArrayList<>();
      for (int r = 0; r < 3; r++) {
        readers.add(
            pool.submit(
                () -> {
                  long[] seen = new long[keys];
                  while (!stop.get()) {
                    for (int k = 0; k < keys; k++) {
                      VersionedValue v = e.get(b("k" + k)).orElseThrow();
                      assertThat(v.timestamp() >= seen[k]).isTrue(); // never goes backwards
                      seen[k] = v.timestamp();
                    }
                    int count = 0;
                    Iterator<Entry> it = e.scan(null, null);
                    while (it.hasNext()) {
                      it.next();
                      count++;
                    }
                    assertThat(count).isEqualTo(keys);
                  }
                  return null;
                }));
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      int round = 0;
      while (System.nanoTime() < deadline) {
        for (int k = 0; k < keys; k++) {
          e.put(b("k" + k), b(Integer.toString(round)), clock.getAndIncrement());
        }
        round++;
      }
      stop.set(true);
      for (Future<?> f : readers) {
        f.get(30, TimeUnit.SECONDS);
      }
      pool.shutdown();
      System.out.printf(
          "concurrent: %d write rounds, %d flushes, %d compactions%n",
          round, e.stats().flushes(), e.stats().compactions());
      assertThat(e.stats().compactions()).isGreaterThan(0L);
    }
  }

  @Test
  void compactedStateSurvivesRestart() {
    EngineConfig cfg = config().autoCompaction(false).build();
    try (Engine e = Engines.open(cfg)) {
      for (int i = 0; i < 2000; i++) {
        e.put(b("k" + (i % 300)), b("v" + i), i + 1);
      }
      e.compact();
      assertThat(e.stats().sstables()).isEqualTo(1);
    }
    try (Engine e = Engines.open(cfg)) {
      assertThat(e.stats().sstables()).isEqualTo(1);
      for (int k = 0; k < 300; k++) {
        assertThat(e.get(b("k" + k)).orElseThrow().value())
            .isEqualTo(b("v" + lastWrite(k, 300, 2000)));
      }
    }
  }

  /** Index of the last write to key {@code k} when writes cycle through {@code keys} keys. */
  private static int lastWrite(int k, int keys, int writes) {
    int last = (writes - 1) / keys * keys + k;
    return last < writes ? last : last - keys;
  }

  private static void waitFor(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 30 s");
      }
      Thread.sleep(20);
    }
  }
}
