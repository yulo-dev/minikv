package dev.kaee.minikv.engine.crash;

import dev.kaee.minikv.engine.Engine;
import dev.kaee.minikv.engine.Engines;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Child process for {@link CrashRecoveryTest}. Applies the workload forever and prints each op's
 * index on stdout after the engine acknowledges it. It never exits on its own; the parent kills it
 * with SIGKILL.
 */
public final class CrashWriter {

  private CrashWriter() {}

  public static void main(String[] args) {
    Path dir = Path.of(args[0]);
    CrashWorkload workload = new CrashWorkload(Long.parseLong(args[1]));
    PrintStream out = System.out;
    Engine engine = Engines.open(CrashWorkload.config(dir));
    while (true) {
      CrashWorkload.Op op = workload.next();
      if (op.delete()) {
        engine.delete(op.keyBytes(), op.timestamp());
      } else {
        engine.put(op.keyBytes(), op.value(), op.timestamp());
      }
      out.println(op.index());
      out.flush();
    }
  }
}
