package test.kasuga.modelling.demo.nebula;

import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;

import java.util.EnumMap;
import java.util.function.DoubleSupplier;

/** Logic-only Kasuga FSM. Its guards read the scene clock; it never advances a second clock. */
final class NebulaWarpSequence {
    enum Phase {
        CRUISE(0, 5.15, true), CHARGING(5.15, 8, false), JUMP(8, 8.95, false),
        WARP(8.95, 12.2, false), EXIT(12.2, 13.75, false), READY(13.75, 15, true);

        final double start, end;
        final boolean canTrigger;
        Phase(double start, double end, boolean canTrigger) {
            this.start = start; this.end = end; this.canTrigger = canTrigger;
        }
    }

    private final StateMachine<DoubleSupplier> machine;

    NebulaWarpSequence(DoubleSupplier cycleTime) {
        machine = StateMachine.builder(cycleTime).layer("warp", layer -> {
            var states = new EnumMap<Phase, State<DoubleSupplier>>(Phase.class);
            for (var phase : Phase.values()) states.put(phase, layer.state(phase.name()));
            layer.initial(states.get(Phase.CRUISE));
            // Direct guards also handle a seek/capture frame that crosses several phase boundaries.
            for (var from : Phase.values()) for (var to : Phase.values()) {
                if (from == to) continue;
                layer.transition(from.name() + "_to_" + to.name(), states.get(from), states.get(to))
                        .when(context -> {
                            double phase = context.owner().getAsDouble();
                            return phase >= to.start && phase < to.end;
                        });
            }
        }).build();
    }

    void update(float dt) { machine.tick(dt); }
    Phase phase() { return Phase.valueOf(machine.layer("warp").active().id()); }
}
