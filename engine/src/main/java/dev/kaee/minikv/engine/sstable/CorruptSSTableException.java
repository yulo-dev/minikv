package dev.kaee.minikv.engine.sstable;

import java.io.IOException;
import java.nio.file.Path;

/** An SSTable failed a checksum or structural check. */
public final class CorruptSSTableException extends IOException {

  private static final long serialVersionUID = 1L;

  public CorruptSSTableException(Path file, String problem) {
    super("corrupt SSTable " + file + ": " + problem);
  }
}
