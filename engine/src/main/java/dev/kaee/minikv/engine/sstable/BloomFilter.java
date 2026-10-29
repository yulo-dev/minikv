package dev.kaee.minikv.engine.sstable;

import dev.kaee.minikv.engine.util.Murmur3;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Bloom filter with Kirsch-Mitzenmacher double hashing: probe {@code i} is {@code h1 + i * h2},
 * where {@code h1, h2} are the two halves of one Murmur3 128-bit hash.
 *
 * <p>Serialized form: {@code numHashes:int | numLongs:int | bits:long[numLongs]}.
 */
public final class BloomFilter {

  private final long[] bits;
  private final long numBits;
  private final int numHashes;

  private BloomFilter(long[] bits, int numHashes) {
    this.bits = bits;
    this.numBits = (long) bits.length * 64;
    this.numHashes = numHashes;
  }

  /** Optimal probe count for a bits-per-key budget: {@code round(bitsPerKey * ln 2)}. */
  static int optimalHashes(int bitsPerKey) {
    return Math.max(1, Math.min(30, (int) Math.round(bitsPerKey * Math.log(2))));
  }

  public boolean mightContain(byte[] key) {
    long[] h = Murmur3.hash128(key, 0);
    long combined = h[0];
    for (int i = 0; i < numHashes; i++) {
      long bit = (combined & Long.MAX_VALUE) % numBits;
      if ((bits[(int) (bit >>> 6)] & (1L << bit)) == 0) {
        return false;
      }
      combined += h[1];
    }
    return true;
  }

  public int numHashes() {
    return numHashes;
  }

  public long numBits() {
    return numBits;
  }

  public byte[] serialize() {
    ByteBuffer buf = ByteBuffer.allocate(8 + bits.length * 8);
    buf.putInt(numHashes).putInt(bits.length);
    for (long word : bits) {
      buf.putLong(word);
    }
    return buf.array();
  }

  public static BloomFilter deserialize(ByteBuffer buf) {
    int numHashes = buf.getInt();
    int numLongs = buf.getInt();
    if (numHashes < 1 || numHashes > 30 || numLongs < 1 || numLongs * 8L != buf.remaining()) {
      throw new IllegalArgumentException("malformed bloom filter block");
    }
    long[] bits = new long[numLongs];
    for (int i = 0; i < numLongs; i++) {
      bits[i] = buf.getLong();
    }
    return new BloomFilter(bits, numHashes);
  }

  /** Collects key hashes, then sizes the filter for the exact key count. */
  public static final class Builder {
    private final int bitsPerKey;
    private long[] hashes = new long[256];
    private int count;

    public Builder(int bitsPerKey) {
      if (bitsPerKey < 1) {
        throw new IllegalArgumentException("bitsPerKey must be positive");
      }
      this.bitsPerKey = bitsPerKey;
    }

    public void add(byte[] key) {
      if (count * 2 + 2 > hashes.length) {
        hashes = Arrays.copyOf(hashes, hashes.length * 2);
      }
      long[] h = Murmur3.hash128(key, 0);
      hashes[count * 2] = h[0];
      hashes[count * 2 + 1] = h[1];
      count++;
    }

    public BloomFilter build() {
      long wantBits = Math.max(64, (long) count * bitsPerKey);
      long[] bits = new long[(int) ((wantBits + 63) / 64)];
      long numBits = (long) bits.length * 64;
      int k = optimalHashes(bitsPerKey);
      for (int i = 0; i < count; i++) {
        long combined = hashes[i * 2];
        for (int j = 0; j < k; j++) {
          long bit = (combined & Long.MAX_VALUE) % numBits;
          bits[(int) (bit >>> 6)] |= 1L << bit;
          combined += hashes[i * 2 + 1];
        }
      }
      return new BloomFilter(bits, k);
    }
  }
}
