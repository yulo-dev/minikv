package dev.kaee.minikv.engine.wal;

import java.io.IOException;
import java.nio.file.Path;

/** A bad record in a segment that is not the last one. Recovery refuses to guess past it. */
public final class CorruptWalException extends IOException {

  private static final long serialVersionUID = 1L;

  public CorruptWalException(Path segment, long offset, String problem) {
    super("corrupt WAL segment " + segment + " at offset " + offset + ": " + problem);
  }
}
