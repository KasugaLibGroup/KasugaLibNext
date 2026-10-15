package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class MultiplexerBenchmark {

    record BenchContext(Map<String, String> properties) implements Context {
        BenchContext {
            properties = Map.copyOf(properties);
        }

        @Override
        public String property(String name) {
            return properties.get(name);
        }

        @Override
        public Blackboard data() {
            return Blackboard.empty();
        }
    }

    static final class BenchVariant extends Variant<BenchVariant> {
        BenchVariant(String id) {
            super(id);
        }
    }

    private static final int VARIANT_COUNT = 100;

    private Multiplexer<BenchContext, BenchVariant> multiplexer;
    private MuxState<BenchVariant> state;
    private BenchContext perTickContext;
    private BenchContext eventContext;

    @Setup
    public void setup() {
        multiplexer = Multiplexer.define(BenchVariant::new, def -> {
            List<BenchVariant> variants = new ArrayList<>();
            for (int i = 0; i < VARIANT_COUNT; i++) {
                variants.add(def.variant("v" + i, v -> {}));
            }
            for (int i = 0; i < VARIANT_COUNT; i++) {
                BenchVariant from = variants.get(i);
                BenchVariant to = variants.get((i + 1) % VARIANT_COUNT);
                def.transition(from, to, t -> t.when(SelectorPredicates.propertyIs("fire", "true")));
            }
            def.initial(variants.get(0));
        });
        state = multiplexer.newState();
        perTickContext = new BenchContext(Map.of("fire", "false"));
        eventContext = new BenchContext(Map.of("fire", "true"));
    }

    /** Called every game tick: context unchanged, no transition fires. */
    @Benchmark
    public void perTick(Blackhole blackhole) {
        multiplexer.advance(state, perTickContext, 0.05f);
        blackhole.consume(state.current());
    }

    /** Called on an event (context changed): a transition fires and commits. */
    @Benchmark
    public void onEvent(Blackhole blackhole) {
        multiplexer.advance(state, eventContext, 0.05f);
        blackhole.consume(state.current());
    }
}
