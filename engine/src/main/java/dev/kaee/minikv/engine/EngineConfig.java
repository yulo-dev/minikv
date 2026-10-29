package dev.kaee.minikv.engine;

import dev.kaee.minikv.engine.sstable.SSTableWriter;
import dev.kaee.minikv.engine.wal.FsyncMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Engine settings. Build with {@link #builder(Path)}. */
public record EngineConfig(
    Path dataDir,
    FsyncMode fsyncMode,
    Duration groupCommitInterval,
    long memtableFlushBytes,
    int maxImmutableMemtables,
    int sstableBlockBytes,
    int bloomBitsPerKey) {

  public static final Duration DEFAULT_GROUP_COMMIT = Duration.ofMillis(5);
  public static final long DEFAULT_MEMTABLE_BYTES = 4L * 1024 * 1024;
  public static final int DEFAULT_MAX_IMMUTABLE_MEMTABLES = 4;

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
    if (maxImmutableMemtables < 1) {
      throw new IllegalArgumentException("maxImmutableMemtables must be at least 1");
    }
    if (sstableBlockBytes < 64) {
      throw new IllegalArgumentException("sstableBlockBytes must be at least 64");
    }
    if (bloomBitsPerKey < 1) {
      throw new IllegalArgumentException("bloomBitsPerKey must be positive");
    }
  }

  public Path walDir() {
    return dataDir.resolve("wal");
  }

  public Path sstableDir() {
    return dataDir.resolve("sst");
  }

  public static Builder builder(Path dataDir) {
    return new Builder(dataDir);
  }

  /**
   * Builder with the spec defaults: BATCH fsync every 5 ms, 4 MiB memtable, 4 KiB blocks, 10 bloom
   * bits per key. Writers stall while 4 frozen memtables wait for flush.
   */
  public static final class Builder {
    private final Path dataDir;
    private FsyncMode fsyncMode = FsyncMode.BATCH;
    private Duration groupCommitInterval = DEFAULT_GROUP_COMMIT;
    private long memtableFlushBytes = DEFAULT_MEMTABLE_BYTES;
    private int maxImmutableMemtables = DEFAULT_MAX_IMMUTABLE_MEMTABLES;
    private int sstableBlockBytes = SSTableWriter.DEFAULT_BLOCK_BYTES;
    private int bloomBitsPerKey = SSTableWriter.DEFAULT_BITS_PER_KEY;

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

    public Builder maxImmutableMemtables(int count) {
      this.maxImmutableMemtables = count;
      return this;
    }

    public Builder sstableBlockBytes(int bytes) {
      this.sstableBlockBytes = bytes;
      return this;
    }

    public Builder bloomBitsPerKey(int bits) {
      this.bloomBitsPerKey = bits;
      return this;
    }

    public EngineConfig build() {
      return new EngineConfig(
          dataDir,
          fsyncMode,
          groupCommitInterval,
          memtableFlushBytes,
          maxImmutableMemtables,
          sstableBlockBytes,
          bloomBitsPerKey);
    }
  }
}
