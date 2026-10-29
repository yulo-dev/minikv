package dev.kaee.minikv.engine.wal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Filesystem durability helpers. */
public final class FileSync {

  private FileSync() {}

  /** Makes created, renamed or deleted directory entries durable. Linux and macOS only. */
  public static void fsyncDirectory(Path dir) throws IOException {
    try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
      ch.force(true);
    }
  }
}
