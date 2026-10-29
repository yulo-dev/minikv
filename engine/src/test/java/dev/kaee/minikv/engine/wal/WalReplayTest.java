package dev.kaee.minikv.engine.wal;

import static dev.kaee.minikv.engine.TestBytes.b;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalReplayTest {

  @TempDir Path dir;

  /** Writes 300 records, rotating every 50, and returns the segment ids. */
  private List<Long> writeSegments() throws IOException {
    SyncPolicy policy = SyncPolicy.create(FsyncMode.ALWAYS, Duration.ofMillis(5));
    try (WriteAheadLog wal = WriteAheadLog.open(dir, policy)) {
      for (int i = 0; i < 300; i++) {
        wal.append(WalRecord.put(b("k" + i), b("v" + i), i + 1));
        if (i % 50 == 49) {
          wal.rotate();
        }
      }
    }
    return WriteAheadLog.listSegments(dir);
  }

  @Test
  void replaysRecordsInOrderAcrossSegments() throws IOException {
    List<Long> ids = writeSegments();
    assertThat(ids).hasSize(7); // six full segments and an empty current one

    List<WalRecord> seen = new ArrayList<>();
    WalReplay.Result r = WalReplay.replay(dir, seen::add);

    assertThat(r.records()).isEqualTo(300L);
    assertThat(r.truncatedBytes()).isEqualTo(0L);
    for (int i = 0; i < 300; i++) {
      assertThat(seen.get(i).timestamp()).isEqualTo((long) i + 1);
    }
  }

  @Test
  void corruptRecordInAnEarlierSegmentIsAHardError() throws IOException {
    List<Long> ids = writeSegments();
    Path first = WriteAheadLog.segmentPath(dir, ids.get(0));
    byte[] bytes = Files.readAllBytes(first);
    bytes[bytes.length / 2] ^= 0x40;
    Files.write(first, bytes);

    assertThatThrownBy(() -> WalReplay.replay(dir, rec -> {}))
        .isInstanceOf(CorruptWalException.class)
        .hasMessageContaining(first.getFileName().toString());
  }

  @Test
  void retiredSegmentsAreDeletedAndIdsKeepIncreasing() throws IOException {
    List<Long> ids = writeSegments();
    SyncPolicy policy = SyncPolicy.create(FsyncMode.NONE, Duration.ofMillis(5));
    try (WriteAheadLog wal = WriteAheadLog.open(dir, policy)) {
      wal.deleteSegmentsThrough(ids.get(ids.size() - 1));
      // Only the segment this instance opened survives; it is current and never deleted.
      assertThat(wal.liveSegments()).hasSize(1);
    }
    List<Long> left = WriteAheadLog.listSegments(dir);
    assertThat(left).hasSize(1);
    try (WriteAheadLog wal = WriteAheadLog.open(dir, policy, left.get(0) + 10)) {
      assertThat(wal.liveSegments().get(1)).isEqualTo(left.get(0) + 11);
    }
  }
}
