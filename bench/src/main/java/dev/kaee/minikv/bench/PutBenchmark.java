package dev.kaee.minikv.bench;

import dev.kaee.minikv.engine.Engine;
import dev.kaee.minikv.engine.EngineConfig;
import dev.kaee.minikv.engine.Engines;
import dev.kaee.minikv.engine.wal.FsyncMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/**
 * Write throughput through the full engine: WAL append and fsync policy, memtable insert, and the
 * background flush and compaction that writes trigger. Each trial starts from an empty directory
 * with the default 4 MiB memtable. Keys cycle through {@link #KEY_SPACE} distinct values, so long
 * runs overwrite instead of growing the data set, and disk use stays bounded.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class PutBenchmark {

  static final long KEY_SPACE = 500_000;

  @Param({"NONE", "BATCH", "ALWAYS"})
  public String fsync;

  @Param({"sequential", "random"})
  public String order;

  @Param({"100", "1024"})
  public int valueSize;

  private Path dir;
  private Engine engine;
  private byte[] value;
  private final AtomicLong counter = new AtomicLong();

  @Setup(Level.Trial)
  public void open() throws IOException {
    dir = BenchData.tempDir("put");
    engine = Engines.open(EngineConfig.builder(dir).fsyncMode(FsyncMode.valueOf(fsync)).build());
    value = BenchData.value(valueSize, 7);
  }

  @TearDown(Level.Trial)
  public void close() throws IOException {
    engine.close();
    BenchData.deleteRecursively(dir);
  }

  private void putNext() {
    long n = counter.incrementAndGet();
    long k =
        "random".equals(order) ? Math.floorMod(BenchData.scramble(n), KEY_SPACE) : n % KEY_SPACE;
    engine.put(BenchData.key(k), value, n);
  }

  /** One writer: under BATCH and ALWAYS each put waits for its own fsync window. */
  @Benchmark
  @Threads(1)
  public void put() {
    putNext();
  }

  /** Sixteen writers: concurrent puts share fsyncs through group commit. */
  @Benchmark
  @Threads(16)
  public void put16Threads() {
    putNext();
  }
}
