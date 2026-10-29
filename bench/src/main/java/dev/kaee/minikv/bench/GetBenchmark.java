package dev.kaee.minikv.bench;

import dev.kaee.minikv.engine.Engine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
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

/**
 * Point reads against one fully compacted SSTable of {@link #KEYS} keys. Misses use keys inside the
 * table's key range, so they get past the min/max check and reach the bloom filter. Running misses
 * at 1 and 10 bloom bits per key shows what the filter saves: at 1 bit most misses read a data
 * block for nothing. The file is small enough to sit in the OS page cache, so this measures the
 * engine's CPU path, not the disk.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
public class GetBenchmark {

  static final int KEYS = 200_000;

  @Param({"100", "1024"})
  public int valueSize;

  @Param({"1", "10"})
  public int bloomBitsPerKey;

  private Path dir;
  private Engine engine;

  @Setup(Level.Trial)
  public void load() throws IOException {
    dir = BenchData.tempDir("get");
    engine = BenchData.loaded(dir, KEYS, valueSize, bloomBitsPerKey);
  }

  @TearDown(Level.Trial)
  public void close() throws IOException {
    engine.close();
    BenchData.deleteRecursively(dir);
  }

  @Benchmark
  public Optional<?> getHit() {
    long i = ThreadLocalRandom.current().nextInt(KEYS);
    return engine.get(BenchData.key(2 * i));
  }

  @Benchmark
  public Optional<?> getMiss() {
    long i = ThreadLocalRandom.current().nextInt(KEYS - 1);
    return engine.get(BenchData.key(2 * i + 1));
  }
}
