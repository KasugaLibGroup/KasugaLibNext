package lib.kasuga.rendering.models.uml.dynamic.fsm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reference scenario: locomotion layer, idle↔walk driven by the typed owner's {@code moving} field. */
class LocomotionTest {

    static final class Actor {
        boolean moving;
        final List<String> events = new ArrayList<>();
    }

    @Test
    void transitionsOnOwnerField() {
        Actor owner = new Actor();
        StateMachine<Actor> m = StateMachine.<Actor>builder(owner)
                .layer("locomotion", layer -> {
                    State<Actor> idle = layer.state("idle");
                    State<Actor> walk = layer.state("walk");
                    layer.initial(idle);
                    layer.transition("idle_to_walk", idle, walk).when(ctx -> ctx.owner().moving);
                    layer.transition("walk_to_idle", walk, idle).when(ctx -> !ctx.owner().moving);
                })
                .build();

        assertEquals("idle", m.layer("locomotion").active().id());
        assertEquals(0, m.version());

        owner.moving = true;
        m.tick();
        assertEquals("walk", m.layer("locomotion").active().id());
        assertTrue(m.version() > 0);

        owner.moving = false;
        m.tick();
        assertEquals("idle", m.layer("locomotion").active().id());
    }
}
