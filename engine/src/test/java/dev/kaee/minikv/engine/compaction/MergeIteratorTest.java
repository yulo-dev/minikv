package dev.kaee.minikv.engine.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaee.minikv.engine.Entry;
import dev.kaee.minikv.engine.VersionedValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MergeIteratorTest {

  @Test
  void keepsOneNewestVersionPerKeyInKeyOrder() {
    Random rnd = new Random(7);
    for (int round = 0; round < 200; round++) {
      int k = 1 + rnd.nextInt(8);
      List<Iterator<Entry>> sources = new ArrayList<>();
      Map<Integer, Long> newest = new HashMap<>();
      for (int s = 0; s < k; s++) {
        TreeMap<Integer, Long> source = new TreeMap<>();
        int n = rnd.nextInt(50);
        for (int i = 0; i < n; i++) {
          // Unique timestamps across all sources, in no particular order.
          source.put(rnd.nextInt(100), (long) rnd.nextInt(1_000_000) * 100 + round);
        }
        List<Entry> entries = new ArrayList<>();
        source.forEach(
            (key, ts) -> {
              entries.add(
                  new Entry(new byte[] {key.byteValue()}, VersionedValue.of(new byte[0], ts)));
              newest.merge(key, ts, Math::max);
            });
        sources.add(entries.iterator());
      }

      List<Integer> keys = new ArrayList<>();
      MergeIterator merged = new MergeIterator(sources);
      while (merged.hasNext()) {
        Entry e = merged.next();
        int key = e.key()[0];
        keys.add(key);
        assertThat(e.value().timestamp()).isEqualTo(newest.get(key).longValue());
      }
      assertThat(keys).containsExactlyElementsOf(new ArrayList<>(new TreeMap<>(newest).keySet()));
    }
  }

  @Test
  void handlesNoSources() {
    assertThat(new MergeIterator(List.of()).hasNext()).isFalse();
  }
}
