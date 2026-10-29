package dev.kaee.minikv.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Test helpers. */
public final class TestBytes {

  private TestBytes() {}

  public static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  public static String s(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public static void copyDir(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path p : (Iterable<Path>) paths::iterator) {
        Path target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }
}
