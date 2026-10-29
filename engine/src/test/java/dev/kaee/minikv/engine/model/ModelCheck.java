package dev.kaee.minikv.engine.model;

import dev.kaee.minikv.engine.Engine;
import dev.kaee.minikv.engine.EngineConfig;
import dev.kaee.minikv.engine.Engines;
import dev.kaee.minikv.engine.Entry;
import dev.kaee.minikv.engine.Timestamps;
import dev.kaee.minikv.engine.VersionedValue;
import dev.kaee.minikv.engine.wal.FsyncMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Runs an operation sequence against the engine and a {@link TreeMap} model, failing on the first
 * difference.
 *
 * <p>The engine uses a tiny memtable and block size so short sequences still rotate memtables,
 * flush and compact in the background, build multi-block tables and recover from restarts.
 * Timestamps follow the hybrid-logical-clock layout with physical time {@code now + ts} ms and the
 * step number in the low bits: unique, recent, and often out of order, so last-write-wins
 * resolution is exercised across every component. Being recent, tombstones stay within the grace
 * period, so the model's tombstones match the engine's exactly; tombstone removal has its own tests
 * in {@code CompactionTest}.
 */
public final class ModelCheck {

  public static final int KEYS = 20;

  private ModelCheck() {}

  public static String key(int i) {
    return String.format(Locale.ROOT, "k%02d", i);
  }

  public static void run(List<Op> ops) throws IOException {
    Path dir = Files.createTempDirectory("minikv-model");
    try {
      run(dir, ops);
    } finally {
      deleteRecursively(dir);
    }
  }

  static void run(Path dir, List<Op> ops) {
    EngineConfig config =
        EngineConfig.builder(dir)
            .fsyncMode(FsyncMode.NONE)
            .memtableFlushBytes(1024)
            .maxImmutableMemtables(2)
            .sstableBlockBytes(128)
            .build();
    TreeMap<String, VersionedValue> model = new TreeMap<>();
    long base = System.currentTimeMillis();
    Engine engine = Engines.open(config);
    try {
      for (int step = 0; step < ops.size(); step++) {
        Op op = ops.get(step);
        String where = "step " + step + " " + op;
        switch (op) {
          case Op.Put p -> {
            byte[] value = new byte[p.valueLen()];
            Arrays.fill(value, (byte) step);
            long ts = Timestamps.of(base + p.ts(), 0, 0) + step;
            engine.put(bytes(key(p.key())), value, ts);
            model.merge(key(p.key()), VersionedValue.of(value, ts), VersionedValue::newer);
          }
          case Op.Delete d -> {
            long ts = Timestamps.of(base + d.ts(), 0, 0) + step;
            engine.delete(bytes(key(d.key())), ts);
            model.merge(key(d.key()), VersionedValue.tombstoneAt(ts), VersionedValue::newer);
          }
          case Op.Get g -> checkGet(engine, model, g.key(), where);
          case Op.Scan s -> checkScan(engine, model, s.from(), s.to(), where);
          case Op.Flush f -> engine.flush();
          case Op.Compact c -> engine.compact();
          case Op.Restart r -> {
            engine.close();
            engine = Engines.open(config);
          }
        }
      }
      for (int k = 0; k < KEYS; k++) {
        checkGet(engine, model, k, "final get");
      }
      checkScan(engine, model, 0, KEYS, "final scan");
      engine.close();
      engine = Engines.open(config);
      for (int k = 0; k < KEYS; k++) {
        checkGet(engine, model, k, "get after final restart");
      }
    } finally {
      engine.close();
    }
  }

  private static void checkGet(
      Engine engine, NavigableMap<String, VersionedValue> model, int k, String where) {
    Optional<VersionedValue> got = engine.get(bytes(key(k)));
    VersionedValue want = model.get(key(k));
    if (want == null) {
      check(got.isEmpty(), where, key(k) + " should be absent, got " + describe(got.orElse(null)));
      return;
    }
    check(got.isPresent(), where, key(k) + " missing, want " + describe(want));
    VersionedValue v = got.orElseThrow();
    check(
        v.timestamp() == want.timestamp()
            && v.tombstone() == want.tombstone()
            && Arrays.equals(v.value(), want.value()),
        where,
        key(k) + " got " + describe(v) + ", want " + describe(want));
  }

  private static void checkScan(
      Engine engine, NavigableMap<String, VersionedValue> model, int a, int b, String where) {
    String from = key(Math.min(a, b));
    String to = key(Math.max(a, b));
    List<String> want = new ArrayList<>();
    for (Map.Entry<String, VersionedValue> e : model.subMap(from, true, to, false).entrySet()) {
      if (!e.getValue().tombstone()) {
        want.add(e.getKey() + "@" + e.getValue().timestamp());
      }
    }
    List<String> got = new ArrayList<>();
    Iterator<Entry> it = engine.scan(bytes(from), bytes(to));
    while (it.hasNext()) {
      Entry e = it.next();
      got.add(new String(e.key(), StandardCharsets.UTF_8) + "@" + e.value().timestamp());
    }
    check(got.equals(want), where, "scan [" + from + ", " + to + ") got " + got + ", want " + want);
  }

  private static String describe(VersionedValue v) {
    if (v == null) {
      return "nothing";
    }
    return (v.tombstone() ? "tombstone" : v.value().length + " bytes") + " @" + v.timestamp();
  }

  private static void check(boolean ok, String where, String message) {
    if (!ok) {
      throw new AssertionError(where + ": " + message);
    }
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
