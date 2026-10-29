package dev.kaee.minikv.engine.wal;

import dev.kaee.minikv.engine.Bytes;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/**
 * WAL record encoding.
 *
 * <p>Layout, big-endian: {@code length:int | crc32c:int | type:byte | timestamp:long | keyLen:int |
 * key | valueLen:int | value}. {@code length} counts the bytes after the crc field; the crc covers
 * the same bytes.
 */
public final class WalCodec {

  static final int HEADER_BYTES = 8;
  static final int MIN_BODY_BYTES = 1 + 8 + 4 + 1 + 4;
  static final int MAX_BODY_BYTES = 1 + 8 + 4 + Bytes.MAX_KEY_BYTES + 4 + Bytes.MAX_VALUE_BYTES;

  private WalCodec() {}

  public static ByteBuffer encode(WalRecord r) {
    int body = 1 + 8 + 4 + r.key().length + 4 + r.value().length;
    ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + body);
    buf.putInt(body).putInt(0);
    buf.put(r.type().code()).putLong(r.timestamp());
    buf.putInt(r.key().length).put(r.key());
    buf.putInt(r.value().length).put(r.value());
    buf.putInt(4, crc(buf.array(), HEADER_BYTES, body));
    return buf.flip();
  }

  static int crc(byte[] data, int offset, int length) {
    CRC32C c = new CRC32C();
    c.update(data, offset, length);
    return (int) c.getValue();
  }

  /**
   * Reads records from one segment until a clean end of file or the first bad record.
   *
   * @return where reading stopped and why
   */
  public static SegmentScan scan(Path file, Consumer<WalRecord> sink) throws IOException {
    long fileBytes = Files.size(file);
    long offset = 0;
    long records = 0;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
      byte[] header = new byte[HEADER_BYTES];
      while (true) {
        int n = readUpTo(in, header, HEADER_BYTES);
        if (n == 0) {
          return new SegmentScan(offset, fileBytes, records, null);
        }
        if (n < HEADER_BYTES) {
          return new SegmentScan(offset, fileBytes, records, "truncated header");
        }
        ByteBuffer h = ByteBuffer.wrap(header);
        int length = h.getInt();
        int expectedCrc = h.getInt();
        if (length < MIN_BODY_BYTES || length > MAX_BODY_BYTES) {
          return new SegmentScan(offset, fileBytes, records, "bad length " + length);
        }
        byte[] body = new byte[length];
        if (readUpTo(in, body, length) < length) {
          return new SegmentScan(offset, fileBytes, records, "truncated body");
        }
        if (crc(body, 0, length) != expectedCrc) {
          return new SegmentScan(offset, fileBytes, records, "crc mismatch");
        }
        WalRecord record = decodeBody(body);
        if (record == null) {
          return new SegmentScan(offset, fileBytes, records, "malformed record");
        }
        sink.accept(record);
        records++;
        offset += HEADER_BYTES + length;
      }
    }
  }

  // Returns null when the body passed its crc but is internally inconsistent.
  private static WalRecord decodeBody(byte[] body) {
    ByteBuffer b = ByteBuffer.wrap(body);
    RecordType type = RecordType.fromCode(b.get());
    long timestamp = b.getLong();
    int keyLen = b.getInt();
    if (type == null || keyLen <= 0 || keyLen > Bytes.MAX_KEY_BYTES || keyLen > b.remaining() - 4) {
      return null;
    }
    byte[] key = new byte[keyLen];
    b.get(key);
    int valueLen = b.getInt();
    if (valueLen < 0 || valueLen != b.remaining()) {
      return null;
    }
    byte[] value = new byte[valueLen];
    b.get(value);
    return new WalRecord(type, timestamp, key, value);
  }

  private static int readUpTo(InputStream in, byte[] buf, int len) throws IOException {
    int total = 0;
    while (total < len) {
      int n = in.read(buf, total, len - total);
      if (n < 0) {
        break;
      }
      total += n;
    }
    return total;
  }
}
