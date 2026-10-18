package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/** Reference scenario: upper_body attack chain — trigger → duration/whenComplete auto-advance + onEnter callbacks. */
class AttackChainTest {

    static final StateVar<Boolean> ATTACK = StateVar.builder(
            Id.fromNamespaceAndPath("kasuga_lib", "test/attack"),
            Boolean.class,
            Codec.BOOL
    ).defaultValue(Boolean.FALSE).ephemeral().build();

    static final class Actor {
        final List<String> events = new ArrayList<>();
    }

    private static StateMachine<Actor> attackMachine(Actor owner) {
        return StateMachine.<Actor>builder(owner)
                .layer("locomotion", layer -> {
                    State<Actor> stand = layer.state("stand");
                    layer.initial(stand);
                })
                .layer("upper_body", layer -> {
                    State<Actor> none = layer.state("none");
                    State<Actor> windup = layer.state("attack.windup").durationTicks(2)
                            .onEnter(ctx -> ctx.owner().events.add("windup"));
                    State<Actor> hit = layer.state("attack.hit").durationTicks(1)
                            .onEnter(ctx -> ctx.owner().events.add("hit"));
                    State<Actor> recover = layer.state("attack.recover").durationTicks(2)
                            .onEnter(ctx -> ctx.owner().events.add("recover"));
                    layer.initial(none);
                    layer.transition("start_attack", none, windup).on(ATTACK);
                    layer.transition("windup_to_hit", windup, hit).whenComplete();
                    layer.transition("hit_to_recover", hit, recover).whenComplete();
                    layer.transition("recover_to_none", recover, none).whenComplete();
                })
                .build();
    }

    @Test
    void chainAdvancesByDurationAndTrigger() {
        Actor owner = new Actor();
        StateMachine<Actor> m = attackMachine(owner);

        assertEquals("none", m.layer("upper_body").active().id());

        m.trigger(ATTACK);
        m.tick();  // none -> windup (onEnter "windup")
        assertEquals("attack.windup", m.layer("upper_body").active().id());

        m.tick();  // windup tick 1 (duration 2)
        assertEquals("attack.windup", m.layer("upper_body").active().id());
        m.tick();  // windup complete -> hit (onEnter "hit")
        assertEquals("attack.hit", m.layer("upper_body").active().id());
        m.tick();  // hit complete -> recover (onEnter "recover")
        assertEquals("attack.recover", m.layer("upper_body").active().id());
        m.tick();  // recover tick 1
        assertEquals("attack.recover", m.layer("upper_body").active().id());
        m.tick();  // recover complete -> none
        assertEquals("none", m.layer("upper_body").active().id());

        assertIterableEquals(List.of("windup", "hit", "recover"), owner.events);
    }
}
