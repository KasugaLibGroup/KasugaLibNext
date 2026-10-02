package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@org.openjdk.jmh.annotations.State(Scope.Thread)
public class StateMachineBenchmark {

    private static final int STATE_COUNT = 100;

    private static final StateVar<Boolean> NEXT = StateVar.builder(
            Id.fromNamespaceAndPath("kasuga_lib", "bench/next"),
            Boolean.class,
            Codec.BOOL
    ).defaultValue(Boolean.FALSE).ephemeral().build();

    private StateMachine<Object> machine;

    @Setup
    public void setup() {
        machine = StateMachine.<Object>builder(new Object())
                .layer("main", layer -> {
                    List<State<Object>> states = new ArrayList<>();
                    for (int i = 0; i < STATE_COUNT; i++) {
                        states.add(layer.state("s" + i));
                    }
                    for (int i = 0; i < STATE_COUNT; i++) {
                        layer.transition("t" + i,
                                states.get(i),
                                states.get((i + 1) % STATE_COUNT)
                        ).on(NEXT);
                    }
                    layer.initial(states.get(0));
                })
                .build();
    }

    /** Called every game tick: no trigger, no transition fires. */
    @Benchmark
    public void perTick(Blackhole blackhole) {
        machine.tick();
        blackhole.consume(machine.layer("main").active().id());
    }

    /** Called on an event: trigger then tick, causing a transition. */
    @Benchmark
    public void onEvent(Blackhole blackhole) {
        machine.trigger(NEXT);
        machine.tick();
        blackhole.consume(machine.layer("main").active().id());
    }
}
