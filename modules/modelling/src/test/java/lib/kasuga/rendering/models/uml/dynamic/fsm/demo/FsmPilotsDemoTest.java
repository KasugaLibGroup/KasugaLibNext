package lib.kasuga.rendering.models.uml.dynamic.fsm.demo;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Living example / demo of building an FSM machine via the DSL.
 */
public class FsmPilotsDemoTest {

    public static final StateVar<Boolean> ACTIVATE = StateVar.builder(
            Id.fromNamespaceAndPath("kasuga_lib", "pilot/activate"),
            Boolean.class,
            Codec.BOOL
    ).defaultValue(Boolean.FALSE).ephemeral().build();

    public static <Owner> StateMachine<Owner> createDemoMachine(Owner owner) {
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

    @Test
    void demoMachineCanBeConstructed() {
        StateMachine<Object> machine = createDemoMachine(new Object());
        assertNotNull(machine);
    }
}
