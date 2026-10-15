package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;

/**
 * Pilot factory: builds a small reference-style machine for an owner. Generic (uses trigger +
 * whenComplete, not owner fields) so it works for any {@code Owner}. A living example of the DSL.
 */
public final class FsmPilots {

    /** Tick-scoped trigger fired to start the demo machine. */
    public static final StateVar<Boolean> ACTIVATE = StateVar.builder(
            Id.fromNamespaceAndPath("kasuga_lib", "pilot/activate"),
            Boolean.class,
            Codec.BOOL
    ).defaultValue(Boolean.FALSE).ephemeral().build();

    private FsmPilots() {}

    public static <Owner> StateMachine<Owner> demo(Owner owner) {
        return StateMachine.<Owner>builder(owner)
                .layer("base", layer -> {
                    State<Owner> idle = layer.state("idle");
                    State<Owner> active = layer.state("active").durationTicks(2);
                    layer.initial(idle);
                    layer.transition("start", idle, active).on(ACTIVATE);
                    layer.transition("loop", active, idle).whenComplete();
                })
                .build();
    }
}
