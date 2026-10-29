package dev.kaee.minikv.engine.wal;

/** When an acknowledged WAL write reaches stable storage. */
public enum FsyncMode {
  /** Every write waits for an fsync. Concurrent writers share one fsync (group commit). */
  ALWAYS,
  /** A background thread fsyncs every interval; writes are acknowledged after that fsync. */
  BATCH,
  /** No fsync. Acknowledged writes can be lost on power failure. Tests and benchmarks only. */
  NONE
}
