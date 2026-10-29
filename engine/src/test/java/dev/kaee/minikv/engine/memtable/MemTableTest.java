package dev.kaee.minikv.engine.memtable;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kaee.minikv.engine.VersionedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemTableTest {

  @Test
  void ordersKeysAsUnsignedBytes() {
    MemTable m = new MemTable();
    byte[][] keys = {{(byte) 0xff}, {(byte) 0x80}, {(byte) 0x00}, {(byte) 0x7f}};
    for (byte[] k : keys) {
      m.apply(k, VersionedValue.of(b("v"), 1));
    }
    List<Integer> order = new ArrayList<>();
    for (Map.Entry<byte[], VersionedValue> e : m.entries()) {
      order.add(e.getKey()[0] & 0xff);
    }
    assertThat(order).containsExactlyElementsOf(List.of(0x00, 0x7f, 0x80, 0xff));
  }

  @Test
  void keepsHighestTimestampWhateverTheArrivalOrder() {
    MemTable m = new MemTable();
    m.apply(b("k"), VersionedValue.of(b("new"), 5));
    m.apply(b("k"), VersionedValue.of(b("old"), 3));
    assertThat(m.get(b("k")).orElseThrow().value()).isEqualTo(b("new"));

    m.apply(b("k"), VersionedValue.tombstoneAt(7));
    m.apply(b("k"), VersionedValue.of(b("late"), 6));
    VersionedValue v = m.get(b("k")).orElseThrow();
    assertThat(v.tombstone()).isTrue();
    assertThat(v.timestamp()).isEqualTo(7L);
  }

  @Test
  void rangeIsHalfOpen() {
    MemTable m = new MemTable();
    for (String k : List.of("a", "b", "c", "d")) {
      m.apply(b(k), VersionedValue.of(b(k), 1));
    }
    assertThat(m.range(b("b"), b("d")).size()).isEqualTo(2);
    assertThat(m.range(null, b("b")).size()).isEqualTo(1);
    assertThat(m.range(b("c"), null).size()).isEqualTo(2);
  }

  @Test
  void frozenMemtableRejectsWrites() {
    MemTable m = new MemTable();
    m.freeze();
    assertThatThrownBy(() -> m.apply(b("k"), VersionedValue.of(b("v"), 1)))
        .isInstanceOf(IllegalStateException.class);
  }
}
