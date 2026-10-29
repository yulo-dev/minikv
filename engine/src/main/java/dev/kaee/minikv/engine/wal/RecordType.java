package dev.kaee.minikv.engine.wal;

/** WAL record type, stored as one byte. */
public enum RecordType {
  PUT((byte) 1),
  DELETE((byte) 2);

  private final byte code;

  RecordType(byte code) {
    this.code = code;
  }

  public byte code() {
    return code;
  }

  /** Returns null for an unknown code so the decoder can treat it as corruption. */
  static RecordType fromCode(byte code) {
    for (RecordType t : values()) {
      if (t.code == code) {
        return t;
      }
    }
    return null;
  }
}
