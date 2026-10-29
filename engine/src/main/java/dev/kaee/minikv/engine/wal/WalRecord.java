package dev.kaee.minikv.engine.wal;

/** One logical WAL entry. A DELETE record has an empty value. */
public record WalRecord(RecordType type, long timestamp, byte[] key, byte[] value) {

  private static final byte[] EMPTY = new byte[0];

  public static WalRecord put(byte[] key, byte[] value, long timestamp) {
    return new WalRecord(RecordType.PUT, timestamp, key, value);
  }

  public static WalRecord delete(byte[] key, long timestamp) {
    return new WalRecord(RecordType.DELETE, timestamp, key, EMPTY);
  }
}
