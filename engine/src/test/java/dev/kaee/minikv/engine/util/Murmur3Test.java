package dev.kaee.minikv.engine.util;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Murmur3Test {

  // Expected values from the reference C++ implementation via the Python mmh3 package:
  // mmh3.hash64(data, seed, x64arch=True, signed=True) -> (h1, h2).
  @Test
  void matchesReferenceVectors() {
    check("", 0, 0L, 0L);
    check("61", 0, -8839064797231613815L, -1822486391929534118L);
    check("68656c6c6f", 0, -3758069500696749310L, 6565844092913065241L);
    check("68656c6c6f2c20776f726c64", 123, 3453566434305547713L, -2622851270537276443L);
    check("000102030405060708090a0b0c0d0e0f", 0, 4920504430128807728L, -6084252774064723899L);
    check(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e",
        42,
        6900886986209544328L,
        3761355361289409259L);
    check(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
        7,
        -4607053826103978000L,
        7409523824475770549L);
    check(
        "ffffffffffffffffffffffffffffffffff",
        0xFFFFFFFF,
        -4180568360630424938L,
        -2850708031991421763L);
  }

  @Test
  void hashesAnOffsetSliceLikeACopy() {
    byte[] text = b("The quick brown fox jumps over the lazy dog");
    long[] whole = Murmur3.hash128(text, 0);
    assertThat(whole[0]).isEqualTo(-2068352364225029268L);
    assertThat(whole[1]).isEqualTo(8809951995912426311L);

    byte[] padded = new byte[text.length + 6];
    System.arraycopy(text, 0, padded, 3, text.length);
    long[] slice = Murmur3.hash128(padded, 3, text.length, 0);
    assertThat(slice[0]).isEqualTo(whole[0]);
    assertThat(slice[1]).isEqualTo(whole[1]);
  }

  private static void check(String hex, int seed, long h1, long h2) {
    long[] h = Murmur3.hash128(HexFormat.of().parseHex(hex), seed);
    assertThat(h[0]).isEqualTo(h1);
    assertThat(h[1]).isEqualTo(h2);
  }
}
