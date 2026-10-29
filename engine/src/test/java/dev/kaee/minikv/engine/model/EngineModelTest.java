package dev.kaee.minikv.engine.model;

import java.io.IOException;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/** Random put/delete/get/scan/flush/compact/restart sequences must behave like a sorted map. */
class EngineModelTest {

  @Property(tries = 1000)
  void engineBehavesLikeASortedMap(@ForAll("operations") List<Op> ops) throws IOException {
    ModelCheck.run(ops);
  }

  @Provide
  Arbitrary<List<Op>> operations() {
    Arbitrary<Integer> key = Arbitraries.integers().between(0, ModelCheck.KEYS - 1);
    Arbitrary<Integer> ts = Arbitraries.integers().between(0, 1000);
    Arbitrary<Integer> valueLen = Arbitraries.integers().between(0, 100);

    Arbitrary<Op> put = Combinators.combine(key, valueLen, ts).as(Op.Put::new);
    Arbitrary<Op> delete = Combinators.combine(key, ts).as(Op.Delete::new);
    Arbitrary<Op> get = key.map(Op.Get::new);
    Arbitrary<Op> scan = Combinators.combine(key, key).as(Op.Scan::new);
    Arbitrary<Op> flush = Arbitraries.just(new Op.Flush());
    Arbitrary<Op> compact = Arbitraries.just(new Op.Compact());
    Arbitrary<Op> restart = Arbitraries.just(new Op.Restart());

    return Arbitraries.frequencyOf(
            Tuple.of(12, put),
            Tuple.of(3, delete),
            Tuple.of(6, get),
            Tuple.of(2, scan),
            Tuple.of(1, flush),
            Tuple.of(1, compact),
            Tuple.of(1, restart))
        .list()
        .ofMinSize(1)
        .ofMaxSize(80);
  }
}
