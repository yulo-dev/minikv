package dev.kaee.minikv.engine;

import static dev.kaee.minikv.engine.TestBytes.b;
import static dev.kaee.minikv.engine.TestBytes.copyDir;
import static org.assertj.core.api.Assertions.assertThat;

import dev.kaee.minikv.engine.wal.FsyncMode;
import dev.kaee.minikv.engine.wal.WalCodec;
import dev.kaee.minikv.engine.wal.WalRecord;
import dev.kaee.minikv.engine.wal.WriteAheadLog;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {

  private static final long SEED = 20261028L;

  @TempDir Path dir;

  private static Engine open(Path dataDir, FsyncMode mode, long memtableBytes) {
    return Engines.open(
        EngineConfig.builder(dataDir).fsyncMode(mode).memtableFlushBytes(memtableBytes).build());
  }

  private static Engine open(Path dataDir) {
    return open(dataDir, FsyncMode.ALWAYS, EngineConfig.DEFAULT_MEMTABLE_BYTES);
  }

  @Test
  void reopenRecoversPutsAndDeletesInBatchAndAlwaysModes() {
    for (FsyncMode mode : List.of(FsyncMode.ALWAYS, FsyncMode.BATCH)) {
      Path data = dir.resolve(mode.name());
      try (Engine e = open(data, mode, EngineConfig.DEFAULT_MEMTABLE_BYTES)) {
        for (int i = 0; i < 500; i++) {
          e.put(b("k" + i), b("v" + i), i + 1);
        }
        for (int i = 0; i < 500; i += 5) {
          e.delete(b("k" + i), 1000 + i);
        }
      }
      try (Engine e = open(data, mode, EngineConfig.DEFAULT_MEMTABLE_BYTES)) {
        assertThat(e.stats().recoveredRecords()).isEqualTo(600L);
        for (int i = 0; i < 500; i++) {
          VersionedValue v = e.get(b("k" + i)).orElseThrow();
          assertThat(v.tombstone()).isEqualTo(i % 5 == 0);
          if (i % 5 != 0) {
            assertThat(v.value()).isEqualTo(b("v" + i));
          }
        }
      }
    }
  }

  /**
   * Simulates a crash mid-append: cut the last segment at a random byte. Bytes past the cut were
   * never acknowledged. Recovery must keep exactly the records that end at or before the cut,
   * truncate the rest, and leave the engine writable and recoverable again.
   */
  @Tag("crash")
  @Test
  void tornTailAtRandomOffsetsLosesOnlyRecordsPastTheCut() throws IOException {
    Random rnd = new Random(SEED);
    Path base = dir.resolve("base");
    List<WalRecord> written = new ArrayList<>();
    try (Engine e = open(base)) {
      for (int i = 0; i < 200; i++) {
        byte[] value = new byte[rnd.nextInt(300)];
        rnd.nextBytes(value);
        byte[] key = b(key(i));
        e.put(key, value, i + 1);
        written.add(WalRecord.put(key, value, i + 1));
      }
    }
    // Record i occupies bytes [boundaries[i], boundaries[i + 1]) of the segment.
    long[] boundaries = new long[written.size() + 1];
    for (int i = 0; i < written.size(); i++) {
      boundaries[i + 1] = boundaries[i] + WalCodec.encode(written.get(i)).limit();
    }
    Path segment = onlySegment(base);
    assertThat(Files.size(segment)).isEqualTo(boundaries[written.size()]);

    TreeSet<Long> cuts = new TreeSet<>(List.of(0L, 1L, boundaries[1], boundaries[200] - 1));
    while (cuts.size() < 100) {
      cuts.add((long) rnd.nextInt((int) boundaries[200]));
    }
    int run = 0;
    for (long cut : cuts) {
      Path data = dir.resolve("cut-" + run++);
      copyDir(base, data);
      try (FileChannel ch = FileChannel.open(onlySegment(data), StandardOpenOption.WRITE)) {
        ch.truncate(cut);
      }
      int survivors = 0;
      while (survivors < 200 && boundaries[survivors + 1] <= cut) {
        survivors++;
      }

      try (Engine e = open(data)) {
        assertThat(e.stats().recoveredRecords()).isEqualTo((long) survivors);
        assertThat(e.stats().truncatedWalBytes()).isEqualTo(cut - boundaries[survivors]);
        for (int i = 0; i < 200; i++) {
          assertThat(e.get(b(key(i))).isPresent()).isEqualTo(i < survivors);
        }
        e.put(b("after-crash"), b("x"), 10_000);
      }
      // The truncated segment is no longer last; it must now read cleanly.
      try (Engine e = open(data)) {
        assertThat(e.stats().recoveredRecords()).isEqualTo((long) survivors + 1);
        assertThat(e.get(b("after-crash")).isPresent()).isTrue();
      }
    }
  }

  @Tag("crash")
  @Test
  void garbageAfterTheLastRecordIsTruncated() throws IOException {
    try (Engine e = open(dir)) {
      for (int i = 0; i < 50; i++) {
        e.put(b("k" + i), b("v" + i), i + 1);
      }
    }
    Path segment = onlySegment(dir);
    byte[] garbage = new byte[37];
    new Random(SEED).nextBytes(garbage);
    Files.write(segment, garbage, StandardOpenOption.APPEND);

    try (Engine e = open(dir)) {
      assertThat(e.stats().recoveredRecords()).isEqualTo(50L);
      assertThat(e.stats().truncatedWalBytes()).isEqualTo(37L);
      assertThat(e.get(b("k49")).orElseThrow().value()).isEqualTo(b("v49"));
    }
  }

  private static String key(int i) {
    return String.format(Locale.ROOT, "k%03d", i);
  }

  private static Path onlySegment(Path dataDir) throws IOException {
    List<Long> ids = WriteAheadLog.listSegments(dataDir.resolve("wal"));
    // Every open starts a fresh segment; the data lives in the first one, later ones are empty.
    Path first = WriteAheadLog.segmentPath(dataDir.resolve("wal"), ids.get(0));
    for (int i = 1; i < ids.size(); i++) {
      assertThat(Files.size(WriteAheadLog.segmentPath(dataDir.resolve("wal"), ids.get(i))))
          .isEqualTo(0L);
    }
    return first;
  }
}
