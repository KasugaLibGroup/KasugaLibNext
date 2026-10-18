package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trigger semantics from doc/fsm-design-review.md (block 1.3 + 2.3):
 * ephemeral triggers are cleared at tick end; buffered (latched) triggers survive across ticks until a
 * transition consumes them — solving the {@code whenComplete + on(trigger)} timing deadlock.
 */
class TriggerLatchTest {

    private static Id rl(String path) {
        return Id.fromNamespaceAndPath("kasuga_lib", path);
    }

    @Test
    void ephemeralTriggerClearsAtTickEnd() {
        StateVar<Boolean> t = StateVar.trigger(rl("test/ephemeral"));
        StateMachine<Object> m = StateMachine.builder(new Object())
                .layer("l", layer -> {
                    State<Object> idle = layer.state("idle");
                    State<Object> go = layer.state("go");
                    layer.initial(idle);
                    layer.transition("i2g", idle, go).on(t);
                })
                .build();

        m.trigger(t);
        assertTrue(m.isTriggered(t));
        m.tick(); // idle → go (trigger fires same tick), then ephemeral cleared at tick end
        assertEquals("go", m.layer("l").active().id());
        assertFalse(m.isTriggered(t), "ephemeral trigger must be cleared at tick end");
    }

    @Test
    void bufferedTriggerSurvivesUntilConsumed() {
        // non-ephemeral bool var — the latch set owns the lifecycle, not the ephemeral sweep
        StateVar<Boolean> buf = StateVar.builder(rl("test/buffered"), Boolean.class, Codec.BOOL)
                .defaultValue(false)
                .build();
        StateMachine<Object> m = StateMachine.builder(new Object())
                .layer("l", layer -> {
                    State<Object> idle = layer.state("idle").durationTicks(1);
                    State<Object> next = layer.state("next");
                    layer.initial(idle);
                    // whenComplete + onBuffered: the trigger can arrive before the source completes
                    layer.transition("i2n", idle, next).whenComplete().onBuffered(buf);
                })
                .build();

        m.triggerBuffered(buf);
        assertTrue(m.isBufferedTriggered(buf));
        m.tick(); // elapsed 0→1, not yet complete; latch must survive (an ephemeral trigger would be gone)
        assertTrue(m.isBufferedTriggered(buf), "buffered trigger must survive across ticks");
        assertEquals("idle", m.layer("l").active().id());
        m.tick(); // elapsed 1, complete; i2n fires and consumes the latch
        assertEquals("next", m.layer("l").active().id());
        assertFalse(m.isBufferedTriggered(buf), "buffered trigger must be consumed when the transition fires");
    }
}
