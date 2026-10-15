package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Complex multi-layer FSM integration test (doc/fsm-design-review.md verification):
 * <ul>
 *   <li>2 independent layer state graphs (locomotion BASE + upper_body OVERRIDE);</li>
 *   <li>typed {@link StateVar} driving conditions ({@code ctx.get(SPEED)});</li>
 *   <li>ephemeral trigger ({@code StateVar.trigger});</li>
 *   <li>{@code lockLayer} interaction (upper_body attack locks locomotion);</li>
 *   <li>{@code whenComplete} auto-advance;</li>
 *   <li>vars set from outside, read inside conditions — the "state binding" data channel.</li>
 * </ul>
 */
class ComplexStateMachineTest {

    private static Id rl(String path) {
        return Id.fromNamespaceAndPath("kasuga_lib", path);
    }

    static final StateVar<Float> SPEED = StateVar.of(rl("test/speed"), Float.class, Codec.FLOAT, 0f);
    static final StateVar<Boolean> ATTACK = StateVar.trigger(rl("test/attack"));

    /** locomotion: idle ↔ walk ↔ run (driven by SPEED var); upper_body: idle → attack(3t) → idle (trigger). */
    static StateMachine<Object> build() {
        return StateMachine.builder(new Object())
                .layer("locomotion", layer -> {
                    layer.base();
                    State<Object> idle = layer.state("idle");
                    State<Object> walk = layer.state("walk");
                    State<Object> run = layer.state("run");
                    layer.initial(idle);
                    layer.transition("i2w", idle, walk).when(ctx -> ctx.get(SPEED) > 0f);
                    layer.transition("w2r", walk, run).when(ctx -> ctx.get(SPEED) > 5f);
                    layer.transition("r2w", run, walk).when(ctx -> ctx.get(SPEED) <= 5f);
                    layer.transition("w2i", walk, idle).when(ctx -> ctx.get(SPEED) <= 0f);
                })
                .layer("upper_body", layer -> {
                    layer.override();
                    State<Object> idle = layer.state("idle");
                    State<Object> attack = layer.state("attack", s -> s.durationTicks(3)
                            .onEnter(ctx -> ctx.lockLayer("locomotion", 3)));
                    layer.initial(idle);
                    layer.transition("a_start", idle, attack).on(ATTACK);
                    layer.transition("a_end", attack, idle).whenComplete();
                })
                .build();
    }

    private static String loco(StateMachine<?> m) { return m.layer("locomotion").active().id(); }
    private static String upper(StateMachine<?> m) { return m.layer("upper_body").active().id(); }

    @Test
    void fullScenarioMultiLayerVarsTriggersLockLayer() {
        StateMachine<Object> m = build();

        // === 0. both layers start at idle ===
        assertEquals("idle", loco(m));
        assertEquals("idle", upper(m));

        // === 1. SPEED=3 → locomotion: idle→walk; upper_body stays idle (independent) ===
        m.mutableVars().set(SPEED, 3f);
        m.tick();
        assertEquals("walk", loco(m), "speed>0 → walk");
        assertEquals("idle", upper(m), "upper_body independent of speed");

        // === 2. SPEED=8 → walk→run ===
        m.mutableVars().set(SPEED, 8f);
        m.tick();
        assertEquals("run", loco(m), "speed>5 → run");

        // === 3. trigger ATTACK → upper_body: idle→attack; attack.onEnter locks locomotion ===
        m.trigger(ATTACK);
        m.tick();
        assertEquals("attack", upper(m), "trigger → attack");
        assertTrue(m.isLayerLocked("locomotion"), "attack.onEnter locked locomotion");

        // === 4. during attack (3 ticks): locomotion frozen even though speed is still 8 ===
        //   run_to_walk condition (speed<=5 → false at 8) wouldn't fire anyway, but let's
        //   confirm lockLayer prevents ANY locomotion transition by dropping speed to 0:
        m.mutableVars().set(SPEED, 0f);  // would normally trigger run→walk→idle
        m.tick();  // tick 1 of attack
        assertEquals("run", loco(m), "locomotion LOCKED — stays run despite speed=0");
        assertEquals("attack", upper(m));
        m.tick();  // tick 2
        assertEquals("run", loco(m), "still locked");
        assertEquals("attack", upper(m));
        m.tick();  // tick 3 → attack duration(3) completes → whenComplete → idle; lock expires
        assertEquals("idle", upper(m), "attack completed → upper_body idle");
        assertFalse(m.isLayerLocked("locomotion"), "lock expired");

        // === 5. lock expired → locomotion can now respond to speed=0 ===
        m.tick();
        assertEquals("walk", loco(m), "lock expired → run→walk (speed<=5)");  // actually run→walk first
        m.tick();
        assertEquals("idle", loco(m), "walk→idle (speed<=0)");

        // === 6. verify SPEED var is still 0 (ephemeral ATTACK was cleared, SPEED persists) ===
        assertEquals(0f, m.vars().get(SPEED));
        assertFalse(m.vars().has(ATTACK), "ephemeral ATTACK cleared after the tick it was triggered");
    }

    @Test
    void layersAdvanceIndependently() {
        StateMachine<Object> m = build();
        // locomotion goes idle→walk→run while upper_body stays idle throughout
        m.mutableVars().set(SPEED, 10f);
        m.tick();
        assertEquals("walk", loco(m));  // speed>0 → walk (not run yet — w2r needs to be IN walk first)
        m.tick();
        assertEquals("run", loco(m));   // now in walk, speed>5 → run
        assertEquals("idle", upper(m), "upper_body untouched by locomotion activity");

        // upper_body attack doesn't affect locomotion's state graph (only lockLayer does)
        m.trigger(ATTACK);
        m.tick();
        assertEquals("attack", upper(m));
        assertEquals("run", loco(m), "locomotion still run (locked, but state unchanged)");
    }
}
