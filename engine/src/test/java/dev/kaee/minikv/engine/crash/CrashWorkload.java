package dev.kaee.minikv.engine.crash;

import dev.kaee.minikv.engine.EngineConfig;
import dev.kaee.minikv.engine.wal.FsyncMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Deterministic write sequence shared by the crashing child process and the verifying parent: both
 * regenerate op {@code i} from the same seed, so the parent knows exactly what was acknowledged.
 */
final class CrashWorkload {

  static final int KEYS = 200;

  /** One write. Timestamps increase with {@code index}, so tombstones may be garbage collected. */
  record Op(int index, int key, boolean delete, int valueLen) {
    byte[] keyBytes() {
      return CrashWorkload.key(key);
    }

    long timestamp() {
      return index + 1L;
    }

    byte[] value() {
      byte[] v = new byte[valueLen];
      Arrays.fill(v, (byte) index);
      if (valueLen >= 4) {
        v[0] = (byte) (index >>> 24);
        v[1] = (byte) (index >>> 16);
        v[2] = (byte) (index >>> 8);
        v[3] = (byte) index;
      }
      return v;
    }
  }

  private final Random random;
  private int next;

  CrashWorkload(long seed) {
    this.random = new Random(seed);
  }

  Op next() {
    int index = next++;
    int key = random.nextInt(KEYS);
    boolean delete = random.nextInt(10) == 0;
    int valueLen = 50 + random.nextInt(2000);
    return new Op(index, key, delete, valueLen);
  }

  static byte[] key(int k) {
    return String.format(Locale.ROOT, "key-%04d", k).getBytes(StandardCharsets.UTF_8);
  }

  /** Small memtable and blocks so flushes and compactions happen during a short run. */
  static EngineConfig config(Path dir) {
    return EngineConfig.builder(dir)
        .fsyncMode(FsyncMode.ALWAYS)
        .memtableFlushBytes(32 * 1024)
        .sstableBlockBytes(1024)
        .build();
  }
}
