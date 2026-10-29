package dev.kaee.minikv.engine.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SizeTieredStrategyTest {

  private final SizeTieredStrategy strategy = new SizeTieredStrategy(4, 6, 100);

  private List<Long> select(Long... sizes) {
    return strategy.select(List.of(sizes), Long::longValue);
  }

  @Test
  void waitsForFourSimilarTables() {
    assertThat(select(1000L, 1100L, 900L)).hasSize(0);
    assertThat(select(1000L, 1100L, 900L, 1200L))
        .containsExactlyElementsOf(List.of(900L, 1000L, 1100L, 1200L));
  }

  @Test
  void doesNotMixVeryDifferentSizes() {
    assertThat(select(1000L, 1000L, 1000L, 50_000L, 900_000L)).hasSize(0);
  }

  @Test
  void smallTablesShareABucket() {
    assertThat(select(1L, 30L, 99L, 5L)).hasSize(4);
  }

  @Test
  void prefersTheBucketWithSmallerTablesAndCapsTheMergeSize() {
    List<Long> picked = select(1000L, 1000L, 1000L, 1000L, 50L, 60L, 70L, 80L, 90L, 95L, 99L, 98L);
    assertThat(picked).containsExactlyElementsOf(List.of(50L, 60L, 70L, 80L, 90L, 95L));
  }
}
