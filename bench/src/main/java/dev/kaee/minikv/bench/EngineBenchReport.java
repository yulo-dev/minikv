package dev.kaee.minikv.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runs the engine benchmarks and writes their results into the engine section of a results file,
 * between {@code <!-- engine:start -->} and {@code <!-- engine:end -->}. Tables come straight from
 * JMH's results; nothing is rounded beyond formatting or filtered.
 *
 * <p>Arguments: output file, and the command line to record in the report.
 */
public final class EngineBenchReport {

  static final String START = "<!-- engine:start -->";
  static final String END = "<!-- engine:end -->";

  private EngineBenchReport() {}

  public static void main(String[] args) throws IOException, RunnerException {
    Path output = Path.of(args[0]);
    String command = args.length > 1 ? args[1] : "./gradlew benchReport";
    boolean quick = Boolean.getBoolean("minikv.bench.quick");

    int forks = 1;
    int warmupIterations = quick ? 1 : 2;
    int measurementIterations = quick ? 1 : 3;
    TimeValue iterationTime = quick ? TimeValue.seconds(1) : TimeValue.seconds(3);
    Options options =
        new OptionsBuilder()
            .include(PutBenchmark.class.getName())
            .include(GetBenchmark.class.getName())
            .include(ScanBenchmark.class.getName())
            .forks(forks)
            .warmupIterations(warmupIterations)
            .warmupTime(iterationTime)
            .measurementIterations(measurementIterations)
            .measurementTime(iterationTime)
            .jvmArgsAppend("-Xmx2g")
            .shouldFailOnError(true)
            .build();
    Collection<RunResult> results = new Runner(options).run();

    StringBuilder md = new StringBuilder();
    md.append(START).append('\n');
    md.append("## Engine (JMH)\n\n");
    if (quick) {
      md.append("**Smoke run with 1-second iterations. These numbers are not meaningful.**\n\n");
    }
    md.append("Command: `").append(command).append("`\n\n");
    md.append(MachineInfo.markdown());
    md.append(
        String.format(
            Locale.ROOT,
            "- JMH: %d fork, %d warmup and %d measurement iterations of %s each, `-Xmx2g`\n",
            forks,
            warmupIterations,
            measurementIterations,
            iterationTime));
    md.append("- Generated: ")
        .append(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        .append("\n\n");
    md.append(
        "Scores are JMH throughput means; ± is JMH's 99.9% confidence interval half-width. "
            + "Data lives in `java.io.tmpdir`; see the machine details for its filesystem.\n\n");
    appendTables(md, results);
    md.append(END).append('\n');

    writeSection(output, md.toString());
    System.out.println("wrote engine results to " + output);
  }

  /** One table per benchmark method; columns are that method's parameters. */
  static void appendTables(StringBuilder md, Collection<RunResult> results) {
    Map<String, List<RunResult>> byMethod = new LinkedHashMap<>();
    for (RunResult r : results) {
      String name = r.getParams().getBenchmark();
      String shortName = name.substring(name.lastIndexOf('.', name.lastIndexOf('.') - 1) + 1);
      byMethod.computeIfAbsent(shortName, k -> new ArrayList<>()).add(r);
    }
    for (Map.Entry<String, List<RunResult>> e : byMethod.entrySet()) {
      TreeSet<String> keys = new TreeSet<>();
      e.getValue().forEach(r -> keys.addAll(r.getParams().getParamsKeys()));
      md.append("### `").append(e.getKey()).append("`\n\n|");
      for (String k : keys) {
        md.append(' ').append(k).append(" |");
      }
      md.append(" threads | score | ± | unit |\n|");
      md.append(" --- |".repeat(keys.size()));
      md.append(" ---: | ---: | ---: | --- |\n");
      for (RunResult r : e.getValue()) {
        md.append('|');
        for (String k : keys) {
          String v = r.getParams().getParam(k);
          md.append(' ').append(v == null ? "" : v).append(" |");
        }
        double error = r.getPrimaryResult().getScoreError();
        md.append(
            String.format(
                Locale.ROOT,
                " %d | %,.0f | %s | %s |\n",
                r.getParams().getThreads(),
                r.getPrimaryResult().getScore(),
                // JMH reports NaN when there are too few iterations for an interval.
                Double.isNaN(error) ? "n/a" : String.format(Locale.ROOT, "%,.0f", error),
                r.getPrimaryResult().getScoreUnit()));
      }
      md.append('\n');
    }
  }

  /** Replaces the text between the markers, or appends the section if the markers are missing. */
  static void writeSection(Path file, String section) throws IOException {
    String existing = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    int start = existing.indexOf(START);
    int end = existing.indexOf(END);
    String updated;
    if (start >= 0 && end > start) {
      int after = end + END.length();
      if (after < existing.length() && existing.charAt(after) == '\n') {
        after++;
      }
      updated = existing.substring(0, start) + section + existing.substring(after);
    } else {
      updated = existing + (existing.isEmpty() ? "" : "\n") + section;
    }
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.writeString(file, updated, StandardCharsets.UTF_8);
  }
}
