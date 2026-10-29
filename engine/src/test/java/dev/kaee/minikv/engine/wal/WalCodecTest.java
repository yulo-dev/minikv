package dev.kaee.minikv.engine.wal;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalCodecTest {

  @TempDir Path dir;

  @Test
  void roundTripsPutsAndDeletes() throws IOException {
    List<WalRecord> written =
        List.of(
            WalRecord.put(b("a"), b("1"), 10),
            WalRecord.delete(b("b"), 11),
            WalRecord.put(b("c"), new byte[0], Long.MAX_VALUE));
    Path file = write(written);

    List<WalRecord> read = new ArrayList<>();
    SegmentScan scan = WalCodec.scan(file, read::add);

    assertThat(scan.clean()).isTrue();
    assertThat(scan.records()).isEqualTo(3L);
    assertThat(read).hasSize(3);
    for (int i = 0; i < written.size(); i++) {
      assertThat(read.get(i).type()).isEqualTo(written.get(i).type());
      assertThat(read.get(i).timestamp()).isEqualTo(written.get(i).timestamp());
      assertThat(read.get(i).key()).isEqualTo(written.get(i).key());
      assertThat(read.get(i).value()).isEqualTo(written.get(i).value());
    }
  }

  @Test
  void flippedByteFailsCrcAndStopsAtThatRecord() throws IOException {
    Path file = write(List.of(WalRecord.put(b("a"), b("1"), 1), WalRecord.put(b("b"), b("2"), 2)));
    byte[] bytes = Files.readAllBytes(file);
    int firstLength = WalCodec.encode(WalRecord.put(b("a"), b("1"), 1)).limit();
    bytes[bytes.length - 1] ^= 0x01;
    Files.write(file, bytes);

    List<WalRecord> read = new ArrayList<>();
    SegmentScan scan = WalCodec.scan(file, read::add);

    assertThat(scan.problem()).isEqualTo("crc mismatch");
    assertThat(scan.validBytes()).isEqualTo((long) firstLength);
    assertThat(read).hasSize(1);
  }

  private Path write(List<WalRecord> records) throws IOException {
    Path file = dir.resolve("segment.log");
    int total = records.stream().mapToInt(r -> WalCodec.encode(r).limit()).sum();
    ByteBuffer all = ByteBuffer.allocate(total);
    records.forEach(r -> all.put(WalCodec.encode(r)));
    Files.write(file, all.array());
    return file;
  }
}
