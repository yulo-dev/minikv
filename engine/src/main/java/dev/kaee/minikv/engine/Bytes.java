package dev.kaee.minikv.engine;

import java.util.Arrays;
import java.util.Comparator;

/** Key ordering and size limits shared by every engine component. */
public final class Bytes {

  public static final int MAX_KEY_BYTES = 1024;
  public static final int MAX_VALUE_BYTES = 1024 * 1024;

  /** Unsigned lexicographic order. The memtable, SSTables and compaction all use this one. */
  public static final Comparator<byte[]> UNSIGNED = Arrays::compareUnsigned;

  private Bytes() {}

  public static void checkKey(byte[] key) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (key.length == 0 || key.length > MAX_KEY_BYTES) {
      throw new IllegalArgumentException(
          "key length " + key.length + " is outside 1.." + MAX_KEY_BYTES + " bytes");
    }
  }

  public static void checkValue(byte[] value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (value.length > MAX_VALUE_BYTES) {
      throw new IllegalArgumentException(
          "value length " + value.length + " exceeds " + MAX_VALUE_BYTES + " bytes");
    }
  }
}
