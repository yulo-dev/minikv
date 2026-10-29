package dev.kaee.minikv.engine.compaction;

import dev.kaee.minikv.engine.Bytes;
import dev.kaee.minikv.engine.Entry;
import dev.kaee.minikv.engine.VersionedValue;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * K-way merge of sorted entry streams into one sorted stream with one entry per key.
 *
 * <p>Each source must be sorted by key with at most one entry per key. When several sources hold
 * the same key, the version with the highest timestamp wins (last-write-wins), regardless of which
 * source it came from. Tombstones are kept; callers decide whether to drop them. Uses a heap of
 * source heads, so each output entry costs {@code O(log k)} for {@code k} sources.
 */
public final class MergeIterator implements Iterator<Entry> {

  private record Head(Entry entry, int source) {}

  private final List<? extends Iterator<Entry>> sources;
  private final PriorityQueue<Head> heap;
  private Entry next;

  public MergeIterator(List<? extends Iterator<Entry>> sources) {
    this.sources = sources;
    this.heap =
        new PriorityQueue<>(
            Math.max(1, sources.size()),
            (a, b) -> {
              int c = Bytes.UNSIGNED.compare(a.entry().key(), b.entry().key());
              return c != 0 ? c : Integer.compare(a.source(), b.source());
            });
    for (int i = 0; i < sources.size(); i++) {
      refill(i);
    }
    next = advance();
  }

  private void refill(int source) {
    Iterator<Entry> it = sources.get(source);
    if (it.hasNext()) {
      heap.add(new Head(it.next(), source));
    }
  }

  private Entry advance() {
    Head head = heap.poll();
    if (head == null) {
      return null;
    }
    refill(head.source());
    byte[] key = head.entry().key();
    VersionedValue best = head.entry().value();
    while (!heap.isEmpty() && Bytes.UNSIGNED.compare(heap.peek().entry().key(), key) == 0) {
      Head dup = heap.poll();
      refill(dup.source());
      best = VersionedValue.newer(best, dup.entry().value());
    }
    return new Entry(key, best);
  }

  @Override
  public boolean hasNext() {
    return next != null;
  }

  @Override
  public Entry next() {
    if (next == null) {
      throw new NoSuchElementException();
    }
    Entry current = next;
    next = advance();
    return current;
  }
}
