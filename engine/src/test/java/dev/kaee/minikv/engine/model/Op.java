package dev.kaee.minikv.engine.model;

/** One step of a model-based test sequence. Keys are small indexes into a fixed key space. */
public sealed interface Op {

  /** Puts a value of {@code valueLen} bytes. {@code ts} is a coarse timestamp; see ModelCheck. */
  record Put(int key, int valueLen, int ts) implements Op {}

  record Delete(int key, int ts) implements Op {}

  record Get(int key) implements Op {}

  record Scan(int from, int to) implements Op {}

  record Flush() implements Op {}

  record Compact() implements Op {}

  record Restart() implements Op {}
}
