package dev.kaee.minikv.engine;

import dev.kaee.minikv.engine.wal.FsyncMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Engine settings. Build with {@link #builder(Path)}. */
public record EngineConfig(
    Path dataDir, FsyncMode fsyncMode, Duration groupCommitInterval, long memtableFlushBytes) {

  public static final Duration DEFAULT_GROUP_COMMIT = Duration.ofMillis(5);
  public static final long DEFAULT_MEMTABLE_BYTES = 4L * 1024 * 1024;

  public EngineConfig {
    Objects.requireNonNull(dataDir, "dataDir");
    Objects.requireNonNull(fsyncMode, "fsyncMode");
    Objects.requireNonNull(groupCommitInterval, "groupCommitInterval");
    if (groupCommitInterval.isNegative() || groupCommitInterval.isZero()) {
      throw new IllegalArgumentException("groupCommitInterval must be positive");
    }
    if (memtableFlushBytes <= 0) {
      throw new IllegalArgumentException("memtableFlushBytes must be positive");
    }
  }

  public Path walDir() {
    return dataDir.resolve("wal");
  }

  public static Builder builder(Path dataDir) {
    return new Builder(dataDir);
  }

  /** Builder with the spec defaults: BATCH fsync every 5 ms, 4 MiB memtable. */
  public static final class Builder {
    private final Path dataDir;
    private FsyncMode fsyncMode = FsyncMode.BATCH;
    private Duration groupCommitInterval = DEFAULT_GROUP_COMMIT;
    private long memtableFlushBytes = DEFAULT_MEMTABLE_BYTES;

    private Builder(Path dataDir) {
      this.dataDir = dataDir;
    }

    public Builder fsyncMode(FsyncMode mode) {
      this.fsyncMode = mode;
      return this;
    }

    public Builder groupCommitInterval(Duration interval) {
      this.groupCommitInterval = interval;
      return this;
    }

    public Builder memtableFlushBytes(long bytes) {
      this.memtableFlushBytes = bytes;
      return this;
    }

    public EngineConfig build() {
      return new EngineConfig(dataDir, fsyncMode, groupCommitInterval, memtableFlushBytes);
    }
  }
}
