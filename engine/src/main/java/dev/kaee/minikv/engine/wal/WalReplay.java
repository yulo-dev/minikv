package dev.kaee.minikv.engine.wal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;

/**
 * Crash recovery: replays every segment in order.
 *
 * <p>A bad record in the last segment is a torn write from a crash mid-append. Those bytes were
 * never acknowledged, so replay stops there and truncates the segment. A bad record in any earlier
 * segment cannot come from a torn write (segments are fsynced before rotation), so it is reported
 * as corruption instead of silently dropping acknowledged data.
 */
public final class WalReplay {

  private static final System.Logger LOG = System.getLogger(WalReplay.class.getName());

  /** Outcome of a replay. */
  public record Result(List<Long> segments, long records, long truncatedBytes) {}

  private WalReplay() {}

  public static Result replay(Path dir, Consumer<WalRecord> sink) throws IOException {
    List<Long> ids = WriteAheadLog.listSegments(dir);
    long records = 0;
    long truncated = 0;
    for (int i = 0; i < ids.size(); i++) {
      Path file = WriteAheadLog.segmentPath(dir, ids.get(i));
      SegmentScan scan = WalCodec.scan(file, sink);
      records += scan.records();
      if (scan.clean()) {
        continue;
      }
      if (i < ids.size() - 1) {
        throw new CorruptWalException(file, scan.validBytes(), scan.problem());
      }
      truncated = scan.fileBytes() - scan.validBytes();
      LOG.log(
          System.Logger.Level.WARNING,
          "torn WAL tail in {0} at offset {1} ({2}); truncating {3} bytes",
          file,
          scan.validBytes(),
          scan.problem(),
          truncated);
      try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
        ch.truncate(scan.validBytes());
        ch.force(true);
      }
    }
    return new Result(ids, records, truncated);
  }
}
