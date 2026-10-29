package dev.kaee.minikv.engine.manifest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One atomic manifest change. A flush adds one table and retires WAL segments; a compaction adds
 * one table and removes the tables it merged.
 *
 * <p>Encoding: {@code addedCount:int | ids:long[] | removedCount:int | ids:long[] |
 * walRetiredThrough:long}.
 */
public record Edit(List<Long> added, List<Long> removed, long walRetiredThrough) {

  public Edit {
    added = List.copyOf(added);
    removed = List.copyOf(removed);
  }

  public static Edit flush(long tableId, long walSegmentThrough) {
    return new Edit(List.of(tableId), List.of(), walSegmentThrough);
  }

  byte[] encode() {
    ByteBuffer b = ByteBuffer.allocate(4 + 8 * added.size() + 4 + 8 * removed.size() + 8);
    b.putInt(added.size());
    added.forEach(b::putLong);
    b.putInt(removed.size());
    removed.forEach(b::putLong);
    b.putLong(walRetiredThrough);
    return b.array();
  }

  static Edit decode(byte[] body) {
    ByteBuffer b = ByteBuffer.wrap(body);
    List<Long> added = readIds(b);
    List<Long> removed = readIds(b);
    long wal = b.getLong();
    if (b.hasRemaining()) {
      throw new IllegalArgumentException("trailing bytes in manifest edit");
    }
    return new Edit(added, removed, wal);
  }

  private static List<Long> readIds(ByteBuffer b) {
    int n = b.getInt();
    if (n < 0 || n > b.remaining() / 8) {
      throw new IllegalArgumentException("bad id count " + n);
    }
    List<Long> ids = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      ids.add(b.getLong());
    }
    return ids;
  }
}
