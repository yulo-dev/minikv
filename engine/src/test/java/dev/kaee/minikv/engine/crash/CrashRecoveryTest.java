package dev.kaee.minikv.engine.crash;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaee.minikv.engine.Engine;
import dev.kaee.minikv.engine.Engines;
import dev.kaee.minikv.engine.Entry;
import dev.kaee.minikv.engine.VersionedValue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kills a writing process with SIGKILL at random moments, possibly mid-fsync, mid-flush or
 * mid-compaction, then checks that every acknowledged write survived and nothing corrupt is
 * returned. Writes use {@code fsync=ALWAYS}, so acknowledged means durable.
 */
@Tag("crash")
class CrashRecoveryTest {

  private static final int ITERATIONS = 60;
  private static final long SEED = 20261029L;

  @TempDir Path tmp;

  @Test
  void acknowledgedWritesSurviveSigkill() throws Exception {
    Random rnd = new Random(SEED);
    long totalAcked = 0;
    for (int iter = 0; iter < ITERATIONS; iter++) {
      Path dir = tmp.resolve("run-" + iter);
      long seed = SEED + iter;
      int killAfterMs = 50 + rnd.nextInt(750);
      int acked = runAndKill(dir, seed, killAfterMs);
      assertThat(acked).isGreaterThan(0);
      verify(dir, seed, acked);
      totalAcked += acked;
    }
    System.out.printf(
        "crash test: %d SIGKILLs, %d acknowledged writes verified%n", ITERATIONS, totalAcked);
  }

  /** Starts the writer, kills it {@code killAfterMs} after its first ack, returns the ack count. */
  private static int runAndKill(Path dir, long seed, int killAfterMs) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        System.getProperty("minikv.test.classpath", System.getProperty("java.class.path"));
    Path stderr = dir.resolveSibling(dir.getFileName() + ".stderr");
    Process child =
        new ProcessBuilder(
                java,
                "-cp",
                classpath,
                CrashWriter.class.getName(),
                dir.toString(),
                Long.toString(seed))
            .redirectError(stderr.toFile())
            .start();

    AtomicInteger lastAck = new AtomicInteger(-1);
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  lastAck.set(Integer.parseInt(line.trim()));
                }
              } catch (IOException | NumberFormatException e) {
                // The pipe breaks when the child dies; whatever was read is what was acked.
              }
            });
    reader.start();

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (lastAck.get() < 0) {
      if (!child.isAlive() || System.nanoTime() > deadline) {
        child.destroyForcibly();
        throw new AssertionError("writer never acknowledged a write: " + Files.readString(stderr));
      }
      Thread.sleep(5);
    }
    Thread.sleep(killAfterMs);
    // SIGKILL on Linux and macOS: no shutdown hooks, no flush. ProcessHandle rather than
    // Process.destroyForcibly(), which also closes our end of the stdout pipe and would drop acks
    // still buffered in it.
    child.toHandle().destroyForcibly();
    child.waitFor(30, TimeUnit.SECONDS);
    reader.join(TimeUnit.SECONDS.toMillis(30));
    return lastAck.get() + 1;
  }

  /**
   * Replays the workload to compute the state after {@code acked} ops. The op after them may or may
   * not have reached the WAL before the kill, so its key may show either outcome.
   */
  private static void verify(Path dir, long seed, int acked) {
    CrashWorkload workload = new CrashWorkload(seed);
    CrashWorkload.Op[] last = new CrashWorkload.Op[CrashWorkload.KEYS];
    for (int i = 0; i < acked; i++) {
      CrashWorkload.Op op = workload.next();
      last[op.key()] = op;
    }
    CrashWorkload.Op inFlight = workload.next();

    try (Engine engine = Engines.open(CrashWorkload.config(dir))) {
      for (int k = 0; k < CrashWorkload.KEYS; k++) {
        Optional<VersionedValue> got = engine.get(CrashWorkload.key(k));
        boolean ok = matches(got, last[k]) || (inFlight.key() == k && matches(got, inFlight));
        if (!ok) {
          throw new AssertionError(
              dir
                  + ": key "
                  + k
                  + " after "
                  + acked
                  + " acked ops: got "
                  + describe(got)
                  + ", want "
                  + describe(last[k])
                  + " or in-flight "
                  + inFlight);
        }
      }
      int live = 0;
      Iterator<Entry> it = engine.scan(null, null);
      while (it.hasNext()) {
        it.next();
        live++;
      }
      assertThat(live <= CrashWorkload.KEYS).isTrue();
    }
  }

  private static boolean matches(Optional<VersionedValue> got, CrashWorkload.Op want) {
    if (want == null || want.delete()) {
      // Never written, or deleted: absent, or a tombstone not yet garbage collected.
      return got.isEmpty()
          || (got.get().tombstone() && want != null && got.get().timestamp() == want.timestamp());
    }
    return got.isPresent()
        && !got.get().tombstone()
        && got.get().timestamp() == want.timestamp()
        && Arrays.equals(got.get().value(), want.value());
  }

  private static String describe(Object o) {
    if (o instanceof Optional<?> opt) {
      if (opt.isEmpty()) {
        return "nothing";
      }
      VersionedValue v = (VersionedValue) opt.get();
      return (v.tombstone() ? "tombstone" : v.value().length + " bytes") + " @" + v.timestamp();
    }
    return String.valueOf(o);
  }
}
