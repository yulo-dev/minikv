package dev.kaee.minikv.engine.wal;

import java.io.IOException;
import java.time.Duration;

/** Strategy for when an appended record counts as durable. One instance per WAL. */
public sealed interface SyncPolicy permits SyncPolicy.Always, SyncPolicy.Batch, SyncPolicy.None {

  /** Blocks until the WAL is durable up to {@code position}, or returns at once for NONE. */
  void awaitDurable(WriteAheadLog wal, long position) throws IOException;

  /** Whether rotation and close must fsync. */
  boolean fsyncs();

  default void start(WriteAheadLog wal) {}

  default void stop() {}

  static SyncPolicy create(FsyncMode mode, Duration batchInterval) {
    return switch (mode) {
      case ALWAYS -> new Always();
      case BATCH -> new Batch(batchInterval);
      case NONE -> new None();
    };
  }

  /** The caller fsyncs; writers that arrive during an fsync share the next one. */
  final class Always implements SyncPolicy {
    @Override
    public void awaitDurable(WriteAheadLog wal, long position) throws IOException {
      wal.syncTo(position);
    }

    @Override
    public boolean fsyncs() {
      return true;
    }
  }

  /** A background thread fsyncs on a fixed delay; writers wait for it. */
  final class Batch implements SyncPolicy {
    private final Duration interval;
    private final GroupCommitter committer = new GroupCommitter();

    Batch(Duration interval) {
      this.interval = interval;
    }

    @Override
    public void awaitDurable(WriteAheadLog wal, long position) throws IOException {
      wal.awaitSynced(position);
    }

    @Override
    public boolean fsyncs() {
      return true;
    }

    @Override
    public void start(WriteAheadLog wal) {
      committer.start(wal, interval);
    }

    @Override
    public void stop() {
      committer.stop();
    }
  }

  /** Never fsyncs. */
  final class None implements SyncPolicy {
    @Override
    public void awaitDurable(WriteAheadLog wal, long position) {}

    @Override
    public boolean fsyncs() {
      return false;
    }
  }
}
