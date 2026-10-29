package dev.kaee.minikv.engine.wal;

/** One logical WAL entry. A DELETE record has an empty value. */
// Arrays are kept by reference to avoid copies on the read and write paths. The public Engine API
// copies caller arrays on the way in. Record equals() compares array identity, so never compare
// these with equals(); compare fields with Arrays.equals instead.
@SuppressWarnings("ArrayRecordComponent")
public record WalRecord(RecordType type, long timestamp, byte[] key, byte[] value) {

  private static final byte[] EMPTY = new byte[0];

  public static WalRecord put(byte[] key, byte[] value, long timestamp) {
    return new WalRecord(RecordType.PUT, timestamp, key, value);
  }

  public static WalRecord delete(byte[] key, long timestamp) {
    return new WalRecord(RecordType.DELETE, timestamp, key, EMPTY);
  }
}
