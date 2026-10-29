package dev.kaee.minikv.engine;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Factory for engine instances. Runs crash recovery before returning. */
public final class Engines {

  private Engines() {}

  public static Engine open(EngineConfig config) {
    try {
      return LsmEngine.open(config);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to open engine at " + config.dataDir(), e);
    }
  }
}
