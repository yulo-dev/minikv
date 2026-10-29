package dev.kaee.minikv.engine.sstable;

import static dev.kaee.minikv.engine.TestBytes.b;
import static dev.kaee.minikv.engine.TestBytes.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kaee.minikv.engine.Bytes;
import dev.kaee.minikv.engine.Entry;
import dev.kaee.minikv.engine.VersionedValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SSTableTest {

  @TempDir Path dir;

  private final Random rnd = new Random(42);

  private static String key(int i) {
    return String.format(Locale.ROOT, "key-%06d", i);
  }

  /** Even-numbered keys only, so odd keys are absent but inside the table's key range. */
  private NavigableMap<byte[], VersionedValue> sample(int n) {
    TreeMap<byte[], VersionedValue> data = new TreeMap<>(Bytes.UNSIGNED);
    for (int i = 0; i < n; i++) {
      long ts = 1L + rnd.nextInt(1_000_000);
      if (i % 7 == 0) {
        data.put(b(key(i * 2)), VersionedValue.tombstoneAt(ts));
      } else {
        byte[] v = new byte[rnd.nextInt(200)];
        rnd.nextBytes(v);
        data.put(b(key(i * 2)), VersionedValue.of(v, ts));
      }
    }
    return data;
  }

  private Path write(NavigableMap<byte[], VersionedValue> data, int blockBytes) throws IOException {
    Path file = dir.resolve("t.sst");
    try (SSTableWriter w = SSTableWriter.builder(file).blockBytes(blockBytes).open()) {
      for (Map.Entry<byte[], VersionedValue> e : data.entrySet()) {
        w.add(e.getKey(), e.getValue());
      }
      w.finish();
    }
    return file;
  }

  @Test
  void pointLookupsReturnEveryEntryAndNothingElse() throws IOException {
    NavigableMap<byte[], VersionedValue> data = sample(5000);
    try (SSTableReader r = SSTableReader.open(write(data, 256))) {
      assertThat(r.entryCount()).isEqualTo(5000L);
      assertThat(r.blockCount()).isGreaterThan(100);
      long maxTs = data.values().stream().mapToLong(VersionedValue::timestamp).max().orElseThrow();
      assertThat(r.maxTimestamp()).isEqualTo(maxTs);
      assertThat(r.minKey()).isEqualTo(b(key(0)));
      assertThat(r.maxKey()).isEqualTo(b(key(9998)));

      for (Map.Entry<byte[], VersionedValue> e : data.entrySet()) {
        VersionedValue got = r.get(e.getKey()).orElseThrow();
        assertThat(got.timestamp()).isEqualTo(e.getValue().timestamp());
        assertThat(got.tombstone()).isEqualTo(e.getValue().tombstone());
        assertThat(got.value()).isEqualTo(e.getValue().value());
      }
      for (int i = 0; i < 5000; i++) {
        assertThat(r.get(b(key(i * 2 + 1))).isPresent()).isFalse();
      }
      assertThat(r.get(b("a")).isPresent()).isFalse();
      assertThat(r.get(b("z")).isPresent()).isFalse();
      // 4999 odd keys fall inside [min, max] (the last one sorts after max) and reach the bloom
      // filter; most should stop there.
      assertThat(r.bloomNegatives() + r.bloomFalsePositives()).isEqualTo(4999L);
      assertThat(r.bloomNegatives()).isGreaterThan(4800L);
    }
  }

  @Test
  void rangeIteratorMatchesASortedMap() throws IOException {
    NavigableMap<byte[], VersionedValue> data = sample(2000);
    try (SSTableReader r = SSTableReader.open(write(data, 512))) {
      assertThat(keys(r.iterator(null, null))).containsExactlyElementsOf(strings(data));
      for (int t = 0; t < 200; t++) {
        int a = rnd.nextInt(4100);
        int z = a + rnd.nextInt(600);
        byte[] from = b(key(a));
        byte[] to = b(key(z));
        List<String> expected = strings(data.subMap(from, true, to, false));
        assertThat(keys(r.iterator(from, to))).containsExactlyElementsOf(expected);
      }
      assertThat(keys(r.iterator(b("zzz"), null))).hasSize(0);
      assertThat(keys(r.iterator(null, b(key(0))))).hasSize(0);
    }
  }

  @Test
  void writerRejectsUnsortedOrDuplicateKeys() throws IOException {
    try (SSTableWriter w = SSTableWriter.builder(dir.resolve("bad.sst")).open()) {
      w.add(b("b"), VersionedValue.of(b("1"), 1));
      assertThatThrownBy(() -> w.add(b("a"), VersionedValue.of(b("2"), 2)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> w.add(b("b"), VersionedValue.of(b("3"), 3)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void abandonedWriterLeavesNoFiles() throws IOException {
    try (SSTableWriter w = SSTableWriter.builder(dir.resolve("gone.sst")).open()) {
      w.add(b("a"), VersionedValue.of(b("1"), 1));
    }
    try (var files = Files.list(dir)) {
      assertThat(files.count()).isEqualTo(0L);
    }
  }

  @Test
  void corruptionIsDetectedNotReturned() throws IOException {
    NavigableMap<byte[], VersionedValue> data = sample(500);
    Path file = write(data, 256);
    byte[] original = Files.readAllBytes(file);

    byte[] badFooter = original.clone();
    badFooter[badFooter.length - 10] ^= 0x01;
    Files.write(file, badFooter);
    assertThatThrownBy(() -> SSTableReader.open(file))
        .isInstanceOf(CorruptSSTableException.class)
        .hasMessageContaining("footer crc");

    byte[] badData = original.clone();
    badData[100] ^= 0x01;
    Files.write(file, badData);
    try (SSTableReader r = SSTableReader.open(file)) {
      assertThatThrownBy(() -> r.get(data.firstKey()))
          .isInstanceOf(CorruptSSTableException.class)
          .hasMessageContaining("data block 0");
    }

    Files.write(file, new byte[] {1, 2, 3});
    assertThatThrownBy(() -> SSTableReader.open(file)).isInstanceOf(CorruptSSTableException.class);
  }

  private static List<String> strings(Map<byte[], VersionedValue> m) {
    List<String> out = new ArrayList<>();
    m.keySet().forEach(k -> out.add(s(k)));
    return out;
  }

  private static List<String> keys(Iterator<Entry> it) {
    List<String> out = new ArrayList<>();
    it.forEachRemaining(e -> out.add(s(e.key())));
    return out;
  }
}
