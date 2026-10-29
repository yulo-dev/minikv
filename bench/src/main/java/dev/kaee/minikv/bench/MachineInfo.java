package dev.kaee.minikv.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Describes the machine a benchmark ran on, for the results file. */
final class MachineInfo {

  private MachineInfo() {}

  static String markdown() {
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    return "- CPU: "
        + cpuModel()
        + ", "
        + Runtime.getRuntime().availableProcessors()
        + " logical cores\n"
        + "- Memory: "
        + totalMemoryGib()
        + "\n"
        + "- OS: "
        + System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + " ("
        + System.getProperty("os.arch")
        + ")\n"
        + "- JVM: "
        + System.getProperty("java.vm.name")
        + " "
        + System.getProperty("java.runtime.version")
        + "\n"
        + "- Data directory: "
        + tmp
        + " on "
        + fileStore(tmp)
        + "\n"
        + "- Commit: "
        + gitCommit()
        + "\n";
  }

  private static String cpuModel() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      String brand = run("sysctl", "-n", "machdep.cpu.brand_string");
      if (brand != null) {
        return brand;
      }
    }
    try {
      for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
        if (line.startsWith("model name")) {
          return line.substring(line.indexOf(':') + 1).trim();
        }
      }
    } catch (IOException e) {
      // fall through
    }
    return "unknown CPU";
  }

  private static String totalMemoryGib() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      return String.format(
          Locale.ROOT, "%.1f GiB", os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
    }
    return "unknown";
  }

  private static String fileStore(Path p) {
    try {
      return Files.getFileStore(p).type();
    } catch (IOException e) {
      return "unknown filesystem";
    }
  }

  private static String gitCommit() {
    String sha = run("git", "rev-parse", "--short", "HEAD");
    if (sha == null) {
      return "unknown";
    }
    String status = run("git", "status", "--porcelain", "--untracked-files=no");
    return status == null || status.isEmpty() ? sha : sha + " (with uncommitted changes)";
  }

  /** Runs a command and returns its trimmed stdout, or null if it fails. */
  private static String run(String... command) {
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
        return null;
      }
      return out;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
