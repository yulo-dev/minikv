package dev.kaee.minikv.engine.sstable;

import java.util.zip.CRC32C;

/**
 * On-disk SSTable layout, all integers big-endian.
 *
 * <pre>
 * data blocks   entries: keyLen:int | key | flags:byte | timestamp:long | valueLen:int | value
 * index block   count:int, then per block: lastKeyLen:int | lastKey | offset:long | length:int |
 *               crc32c:int
 * bloom block   see BloomFilter
 * meta block    minKeyLen:int | minKey | maxKeyLen:int | maxKey
 * footer (80 B) magic:long | version:int | (offset:long | length:int | crc32c:int) for index,
 *               bloom and meta | entryCount:long | maxTimestamp:long | footerCrc32c:int
 * </pre>
 *
 * <p>Every block is checksummed, so a reader never returns data from a damaged file.
 */
final class SSTableFormat {

  static final long MAGIC = 0x4d696e694b565353L; // "MiniKVSS"
  static final int VERSION = 1;
  static final int FOOTER_BYTES = 8 + 4 + 3 * (8 + 4 + 4) + 8 + 8 + 4;
  static final byte FLAG_TOMBSTONE = 1;

  private SSTableFormat() {}

  static int crc(byte[] data, int offset, int length) {
    CRC32C c = new CRC32C();
    c.update(data, offset, length);
    return (int) c.getValue();
  }
}
