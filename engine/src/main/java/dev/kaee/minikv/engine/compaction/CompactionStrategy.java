package dev.kaee.minikv.engine.compaction;

import java.util.List;
import java.util.function.ToLongFunction;

/** Strategy that decides which SSTables to merge next. */
public interface CompactionStrategy {

  /** Tables to merge now, or an empty list if nothing is worth compacting. */
  <T> List<T> select(List<T> tables, ToLongFunction<T> sizeBytes);
}
