package dev.kaee.minikv.engine.wal;

/**
 * Result of reading one segment.
 *
 * @param validBytes length of the prefix made of complete, crc-valid records
 * @param problem null for a clean end of file, otherwise why reading stopped
 */
public record SegmentScan(long validBytes, long fileBytes, long records, String problem) {

  public boolean clean() {
    return problem == null;
  }
}
