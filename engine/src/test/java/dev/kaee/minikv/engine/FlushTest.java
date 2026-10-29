package dev.kaee.minikv.engine;

import static dev.kaee.minikv.engine.TestBytes.b;
import static dev.kaee.minikv.engine.TestBytes.s;
import static org.assertj.core.api.Assertions.assertThat;

import dev.kaee.minikv.engine.manifest.Manifest;
import dev.kaee.minikv.engine.wal.FsyncMode;
import dev.kaee.minikv.engine.wal.WriteAheadLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlushTest {

  @TempDir Path dir;

  private Engine open(long memtableBytes, int maxImmutable) {
    return Engines.open(
        EngineConfig.builder(dir)
            .fsyncMode(FsyncMode.NONE)
            .memtableFlushBytes(memtableBytes)
            .maxImmutableMemtables(maxImmutable)
            .sstableBlockBytes(512)
            .build());
  }

  @Test
  void flushMovesDataToSSTablesAndRetiresWal() {
    try (Engine e = open(4096, 4)) {
      for (int i = 0; i < 2000; i++) {
        e.put(b("k" + i), b("value-" + i), i + 1);
      }
      e.flush();
      EngineStats st = e.stats();
      assertThat(st.immutableMemtables()).isEqualTo(0);
      assertThat(st.activeMemtableBytes()).isEqualTo(0L);
      assertThat(st.sstables()).isGreaterThan(10);
      assertThat(st.walSegments()).isEqualTo(1);
    }
    try (Engine e = open(4096, 4)) {
      assertThat(e.stats().recoveredRecords()).isEqualTo(0L);
      for (int i = 0; i < 2000; i++) {
        assertThat(e.get(b("k" + i)).orElseThrow().value()).isEqualTo(b("value-" + i));
      }
    }
  }

  @Test
  void newestTimestampWinsAcrossMemtablesAndTables() {
    try (Engine e = open(1 << 20, 4)) {
      e.put(b("a"), b("ts100"), 100);
      e.put(b("b"), b("live"), 10);
      e.flush();
      e.put(b("a"), b("ts50"), 50); // older version arrives late, lands in a newer component
      e.delete(b("b"), 20);
      assertThat(e.get(b("a")).orElseThrow().value()).isEqualTo(b("ts100"));
      assertThat(e.get(b("b")).orElseThrow().tombstone()).isTrue();
      assertThat(keys(e)).containsExactlyElementsOf(List.of("a"));

      e.flush();
      assertThat(e.stats().sstables()).isEqualTo(2);
      assertThat(e.get(b("a")).orElseThrow().value()).isEqualTo(b("ts100"));
      assertThat(e.get(b("b")).orElseThrow().tombstone()).isTrue();
      assertThat(keys(e)).containsExactlyElementsOf(List.of("a"));
    }
  }

  @Test
  void writersStallButFinishWhenFlushFallsBehind() {
    try (Engine e = open(1024, 1)) {
      for (int i = 0; i < 3000; i++) {
        e.put(b("k" + i), new byte[64], i + 1);
      }
      for (int i = 0; i < 3000; i++) {
        assertThat(e.get(b("k" + i)).isPresent()).isTrue();
      }
    }
  }

  @Test
  void openRemovesWhatACrashLeavesBehind() throws IOException {
    try (Engine e = open(1 << 20, 4)) {
      e.put(b("kept"), b("v"), 1);
      e.flush();
    }
    EngineConfig cfg = EngineConfig.builder(dir).build();
    // A table written but never recorded in the manifest, a temp file, and a retired WAL segment.
    Path orphan = LsmEngine.sstablePath(cfg, 999);
    Path temp = cfg.sstableDir().resolve("sst-00000000000000001000.sst.tmp");
    Path retiredWal = WriteAheadLog.segmentPath(cfg.walDir(), 1);
    Files.write(orphan, new byte[] {1, 2, 3});
    Files.write(temp, new byte[] {4, 5, 6});
    Files.write(retiredWal, new byte[0], StandardOpenOption.CREATE);
    // A torn manifest tail from a crash mid-append.
    Files.write(
        cfg.dataDir().resolve(Manifest.FILE_NAME),
        new byte[] {0, 0, 0, 99, 0},
        StandardOpenOption.APPEND);

    try (Engine e = open(1 << 20, 4)) {
      assertThat(Files.exists(orphan)).isFalse();
      assertThat(Files.exists(temp)).isFalse();
      assertThat(Files.exists(retiredWal)).isFalse();
      assertThat(e.get(b("kept")).orElseThrow().value()).isEqualTo(b("v"));
      assertThat(e.stats().sstables()).isEqualTo(1);
    }
  }

  private static List<String> keys(Engine e) {
    List<String> out = new ArrayList<>();
    e.scan(null, null).forEachRemaining(en -> out.add(s(en.key())));
    return out;
  }
}
