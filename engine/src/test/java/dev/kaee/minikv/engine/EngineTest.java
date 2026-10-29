package dev.kaee.minikv.engine;

import static dev.kaee.minikv.engine.TestBytes.b;
import static dev.kaee.minikv.engine.TestBytes.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kaee.minikv.engine.wal.FsyncMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {

  @TempDir Path dir;

  private Engine open(long memtableBytes) {
    return Engines.open(
        EngineConfig.builder(dir)
            .fsyncMode(FsyncMode.NONE)
            .memtableFlushBytes(memtableBytes)
            .build());
  }

  @Test
  void rejectsKeysAndValuesOverTheLimits() {
    try (Engine e = open(EngineConfig.DEFAULT_MEMTABLE_BYTES)) {
      assertThatThrownBy(() -> e.put(new byte[Bytes.MAX_KEY_BYTES + 1], b("v"), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("key length 1025");
      assertThatThrownBy(() -> e.put(new byte[0], b("v"), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("key length 0");
      assertThatThrownBy(() -> e.put(b("k"), new byte[Bytes.MAX_VALUE_BYTES + 1], 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exceeds 1048576");
      e.put(new byte[Bytes.MAX_KEY_BYTES], new byte[Bytes.MAX_VALUE_BYTES], 1);
    }
  }

  @Test
  void getReturnsTombstonesSoCallersCanCompareVersions() {
    try (Engine e = open(EngineConfig.DEFAULT_MEMTABLE_BYTES)) {
      e.put(b("k"), b("v"), 1);
      e.delete(b("k"), 2);
      VersionedValue v = e.get(b("k")).orElseThrow();
      assertThat(v.tombstone()).isTrue();
      assertThat(v.timestamp()).isEqualTo(2L);
      assertThat(e.get(b("missing")).isPresent()).isFalse();
    }
  }

  @Test
  void scanIsHalfOpenAndSkipsTombstones() {
    try (Engine e = open(EngineConfig.DEFAULT_MEMTABLE_BYTES)) {
      for (String k : List.of("a", "b", "c", "d", "e")) {
        e.put(b(k), b(k.toUpperCase(Locale.ROOT)), 1);
      }
      e.delete(b("c"), 2);
      assertThat(keys(e.scan(b("b"), b("e")))).containsExactlyElementsOf(List.of("b", "d"));
      assertThat(keys(e.scan(null, null))).containsExactlyElementsOf(List.of("a", "b", "d", "e"));
    }
  }

  @Test
  void readsCompareVersionsAcrossOlderComponents() {
    try (Engine e = open(512)) {
      e.put(b("k"), b("newer"), 100);
      for (int i = 0; i < 50; i++) {
        e.put(b("filler" + i), new byte[32], 1);
      }
      // The older memtable may already be flushed by now; either way it is no longer active.
      EngineStats st = e.stats();
      assertThat(st.immutableMemtables() + st.sstables()).isGreaterThan(0);
      // An older timestamp that arrives later lands in a newer memtable and must not win.
      e.put(b("k"), b("older"), 50);
      assertThat(e.get(b("k")).orElseThrow().value()).isEqualTo(b("newer"));
      assertThat(keys(e.scan(b("k"), b("l")))).containsExactlyElementsOf(List.of("k"));
      for (int i = 0; i < 50; i++) {
        assertThat(e.get(b("filler" + i)).isPresent()).isTrue();
      }
    }
  }

  @Test
  void closedEngineRejectsCalls() {
    Engine e = open(EngineConfig.DEFAULT_MEMTABLE_BYTES);
    e.close();
    assertThatThrownBy(() -> e.put(b("k"), b("v"), 1)).isInstanceOf(IllegalStateException.class);
  }

  private static List<String> keys(Iterator<Entry> it) {
    List<String> out = new ArrayList<>();
    it.forEachRemaining(en -> out.add(s(en.key())));
    return out;
  }
}
