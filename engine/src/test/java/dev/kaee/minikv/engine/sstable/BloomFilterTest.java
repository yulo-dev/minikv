package dev.kaee.minikv.engine.sstable;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BloomFilterTest {

  private static final int KEYS = 100_000;
  private static final int PROBES = 1_000_000;

  @Test
  void hasNoFalseNegatives() {
    BloomFilter f = build(10);
    for (int i = 0; i < KEYS; i++) {
      assertThat(f.mightContain(b("present-" + i))).isTrue();
    }
  }

  @Test
  void measuredFalsePositiveRateIsWithinTwiceTheory() {
    for (int bitsPerKey : new int[] {6, 10, 14}) {
      BloomFilter f = build(bitsPerKey);
      int falsePositives = 0;
      for (int i = 0; i < PROBES; i++) {
        if (f.mightContain(b("absent-" + i))) {
          falsePositives++;
        }
      }
      double measured = (double) falsePositives / PROBES;
      int k = f.numHashes();
      double theory = Math.pow(1 - Math.exp(-(double) k * KEYS / f.numBits()), k);
      System.out.printf(
          "bloom bits/key=%d k=%d keys=%d probes=%d measured FPR=%.5f theory=%.5f%n",
          bitsPerKey, k, KEYS, PROBES, measured, theory);
      assertThat(measured < theory * 2).isTrue();
    }
  }

  @Test
  void survivesSerialization() {
    BloomFilter f = build(10);
    BloomFilter copy = BloomFilter.deserialize(ByteBuffer.wrap(f.serialize()));
    assertThat(copy.numHashes()).isEqualTo(f.numHashes());
    assertThat(copy.numBits()).isEqualTo(f.numBits());
    for (int i = 0; i < 1000; i++) {
      assertThat(copy.mightContain(b("present-" + i))).isTrue();
      assertThat(copy.mightContain(b("absent-" + i))).isEqualTo(f.mightContain(b("absent-" + i)));
    }
  }

  private static BloomFilter build(int bitsPerKey) {
    BloomFilter.Builder builder = new BloomFilter.Builder(bitsPerKey);
    for (int i = 0; i < KEYS; i++) {
      builder.add(b("present-" + i));
    }
    return builder.build();
  }
}
