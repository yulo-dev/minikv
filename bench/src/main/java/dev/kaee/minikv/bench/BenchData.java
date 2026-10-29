package dev.kaee.minikv.bench;

import dev.kaee.minikv.engine.Engine;
import dev.kaee.minikv.engine.EngineConfig;
import dev.kaee.minikv.engine.Engines;
import dev.kaee.minikv.engine.wal.FsyncMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Keys, values and data directories shared by the benchmarks. */
final class BenchData {

  private BenchData() {}

  /** 8-byte big-endian key, so numeric order is key order. */
  static byte[] key(long n) {
    byte[] k = new byte[8];
    for (int i = 7; i >= 0; i--) {
      k[i] = (byte) n;
      n >>>= 8;
    }
    return k;
  }

  /** Bijective scramble of a counter, giving a random-looking but repeatable key order. */
  static long scramble(long n) {
    n ^= n >>> 33;
    n *= 0xff51afd7ed558ccdL;
    n ^= n >>> 33;
    n *= 0xc4ceb9fe1a85ec53L;
    n ^= n >>> 33;
    return n;
  }

  static byte[] value(int size, long seed) {
    byte[] v = new byte[size];
    long x = seed;
    for (int i = 0; i < size; i++) {
      x = x * 6364136223846793005L + 1442695040888963407L;
      v[i] = (byte) (x >>> 56);
    }
    return v;
  }

  static Path tempDir(String name) throws IOException {
    return Files.createTempDirectory("minikv-bench-" + name);
  }

  static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  /**
   * Loads {@code count} keys {@code 0, 2, 4, ...} (so odd keys are misses inside the key range)
   * with fsync off, then fully compacts, so reads hit one SSTable rather than the memtable.
   */
  static Engine loaded(Path dir, int count, int valueSize, int bloomBitsPerKey) {
    EngineConfig config =
        EngineConfig.builder(dir)
            .fsyncMode(FsyncMode.NONE)
            .bloomBitsPerKey(bloomBitsPerKey)
            .build();
    Engine engine = Engines.open(config);
    byte[] value = value(valueSize, 42);
    for (int i = 0; i < count; i++) {
      engine.put(key(2L * i), value, i + 1L);
    }
    engine.compact();
    return engine;
  }
}
