package dev.kaee.minikv.engine.wal;

/** When an acknowledged WAL write reaches stable storage. */
public enum FsyncMode {
  /**
   * Every write waits for an fsync. Concurrent writers share one fsync through leader/follower
   * group commit. The default.
   */
  ALWAYS,
  /**
   * A background thread fsyncs every interval; writes are acknowledged after that fsync. Each
   * writer waits up to one interval, so a single writer is capped near one write per interval.
   */
  BATCH,
  /** No fsync. Acknowledged writes can be lost on power failure. Tests and benchmarks only. */
  NONE
}
