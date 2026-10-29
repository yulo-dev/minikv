package dev.kaee.minikv.bench;

import dev.kaee.minikv.engine.Engine;
import dev.kaee.minikv.engine.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.infra.Blackhole;

/** Range scans of {@link #SCAN_LENGTH} consecutive keys from a random start, one op per scan. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
public class ScanBenchmark {

  static final int SCAN_LENGTH = 1000;

  @Param({"100", "1024"})
  public int valueSize;

  private Path dir;
  private Engine engine;

  @Setup(Level.Trial)
  public void load() throws IOException {
    dir = BenchData.tempDir("scan");
    engine = BenchData.loaded(dir, GetBenchmark.KEYS, valueSize, 10);
  }

  @TearDown(Level.Trial)
  public void close() throws IOException {
    engine.close();
    BenchData.deleteRecursively(dir);
  }

  @Benchmark
  public int scan1000(Blackhole bh) {
    long start = ThreadLocalRandom.current().nextInt(GetBenchmark.KEYS - SCAN_LENGTH);
    Iterator<Entry> it =
        engine.scan(BenchData.key(2 * start), BenchData.key(2 * (start + SCAN_LENGTH)));
    int n = 0;
    while (it.hasNext()) {
      bh.consume(it.next());
      n++;
    }
    if (n != SCAN_LENGTH) {
      throw new IllegalStateException("scan returned " + n + " entries");
    }
    return n;
  }
}
