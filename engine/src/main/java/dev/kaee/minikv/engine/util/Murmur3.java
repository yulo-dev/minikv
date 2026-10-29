package dev.kaee.minikv.engine.util;

/**
 * MurmurHash3 x64 128-bit, written from Austin Appleby's public-domain reference algorithm.
 *
 * <p>Used for bloom filter probes and, later, ring placement. Output matches the reference
 * implementation; {@code Murmur3Test} checks it against published vectors.
 */
public final class Murmur3 {

  private static final long C1 = 0x87c37b91114253d5L;
  private static final long C2 = 0x4cf5ad432745937fL;

  private Murmur3() {}

  /** Returns {h1, h2}. The seed is treated as unsigned 32-bit, as in the reference. */
  public static long[] hash128(byte[] data, int seed) {
    return hash128(data, 0, data.length, seed);
  }

  public static long[] hash128(byte[] data, int offset, int length, int seed) {
    long h1 = seed & 0xffffffffL;
    long h2 = h1;
    int blocks = length / 16;
    for (int i = 0; i < blocks; i++) {
      int at = offset + i * 16;
      long k1 = getLongLe(data, at);
      long k2 = getLongLe(data, at + 8);

      h1 ^= mixK1(k1);
      h1 = Long.rotateLeft(h1, 27);
      h1 += h2;
      h1 = h1 * 5 + 0x52dce729;

      h2 ^= mixK2(k2);
      h2 = Long.rotateLeft(h2, 31);
      h2 += h1;
      h2 = h2 * 5 + 0x38495ab5;
    }

    int tail = offset + blocks * 16;
    int rem = length & 15;
    long k1 = 0;
    long k2 = 0;
    for (int i = rem - 1; i >= 8; i--) {
      k2 ^= (data[tail + i] & 0xffL) << ((i - 8) * 8);
    }
    for (int i = Math.min(rem, 8) - 1; i >= 0; i--) {
      k1 ^= (data[tail + i] & 0xffL) << (i * 8);
    }
    if (rem > 8) {
      h2 ^= mixK2(k2);
    }
    if (rem > 0) {
      h1 ^= mixK1(k1);
    }

    h1 ^= length;
    h2 ^= length;
    h1 += h2;
    h2 += h1;
    h1 = fmix64(h1);
    h2 = fmix64(h2);
    h1 += h2;
    h2 += h1;
    return new long[] {h1, h2};
  }

  private static long mixK1(long k1) {
    k1 *= C1;
    k1 = Long.rotateLeft(k1, 31);
    return k1 * C2;
  }

  private static long mixK2(long k2) {
    k2 *= C2;
    k2 = Long.rotateLeft(k2, 33);
    return k2 * C1;
  }

  private static long fmix64(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }

  private static long getLongLe(byte[] b, int i) {
    return (b[i] & 0xffL)
        | (b[i + 1] & 0xffL) << 8
        | (b[i + 2] & 0xffL) << 16
        | (b[i + 3] & 0xffL) << 24
        | (b[i + 4] & 0xffL) << 32
        | (b[i + 5] & 0xffL) << 40
        | (b[i + 6] & 0xffL) << 48
        | (b[i + 7] & 0xffL) << 56;
  }
}
