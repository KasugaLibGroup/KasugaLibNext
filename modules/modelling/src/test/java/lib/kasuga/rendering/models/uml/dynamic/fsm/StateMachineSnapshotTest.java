package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server snapshot → client conform: state/elapsed reproduction, transition restore, version bumps. */
class StateMachineSnapshotTest {

    static final StateVar<Boolean> ATTACK = StateVar.builder(
            Id.fromNamespaceAndPath("kasuga_lib", "snapshot/attack"),
            Boolean.class,
            Codec.BOOL
    ).defaultValue(Boolean.FALSE).ephemeral().build();

    /** idle (duration 2) → walk via when-complete crossfade + a trigger layer for multi-layer coverage. */
    private static StateMachine<Object> machine() {
        return StateMachine.builder(new Object())
                .layer("locomotion", layer -> {
                    State<Object> idle = layer.state("idle").durationTicks(2);
                    State<Object> walk = layer.state("walk");
                    layer.initial(idle);
                    layer.transition("idle_to_walk", idle, walk).whenComplete().crossFade(0.25f);
                })
                .layer("upper_body", layer -> {
                    State<Object> none = layer.state("none");
                    State<Object> attack = layer.state("attack");
                    layer.initial(none);
                    layer.transition("none_to_attack", none, attack).on(ATTACK);
                })
                .build();
    }

    @Test
    void snapshotCapturesActiveStatesAndElapsed() {
        StateMachine<Object> a = machine();
        a.tick();
        a.tick();
        a.tick(); // idle (duration 2) completes → crossfade idle→walk in flight (elapsed 0)
        a.tick(); // transition advances to 0.05s
        StateMachineSnapshot snapshot = a.snapshot();
        assertEquals(a.version(), snapshot.version());
        assertEquals(2, snapshot.layers().size());

        StateMachineSnapshot.LayerState loco = snapshot.layers().get(0);
        assertEquals("locomotion", loco.layerId());
        assertEquals("idle", loco.stateId());
        assertEquals(3, loco.elapsedTicks());
        assertEquals("idle_to_walk", loco.transitionId());
        assertTrue(loco.transitionElapsedSeconds() > 0f);

        StateMachineSnapshot.LayerState upper = snapshot.layers().get(1);
        assertEquals("upper_body", upper.layerId());
        assertEquals("none", upper.stateId());
        assertNull(upper.transitionId());
    }

    @Test
    void conformReproducesServerStateAndBumpsVersionOnce() {
        StateMachine<Object> a = machine();
        a.tick();
        a.tick();
        a.tick();
        StateMachineSnapshot snapshot = a.snapshot();

        StateMachine<Object> b = machine();
        int v0 = b.version();
        assertTrue(b.conform(snapshot));
        assertEquals(v0 + 1, b.version());

        assertEquals(a.layer("locomotion").active().id(), b.layer("locomotion").active().id());
        assertEquals(a.layer("locomotion").stateElapsedTicks(), b.layer("locomotion").stateElapsedTicks());
        assertNotNull(b.layer("locomotion").activeTransition());
        assertEquals(a.layer("locomotion").activeTransition().id(), b.layer("locomotion").activeTransition().id());
        assertEquals(a.layer("locomotion").transitionElapsed(), b.layer("locomotion").transitionElapsed(), 1e-4f);
        assertEquals("none", b.layer("upper_body").active().id());

        // a second conform with the same snapshot also bumps (server version ordering is the
        // client's dedup concern, not conform's)
        assertTrue(b.conform(snapshot));
        assertEquals(v0 + 2, b.version());
    }

    @Test
    void transitionSnapshotConformsAndFadeContinues() {
        StateMachine<Object> b = machine();
        assertTrue(b.conform(new StateMachineSnapshot(5, List.of(
                new StateMachineSnapshot.LayerState("locomotion", "idle", 2, "idle_to_walk", 0.1f),
                new StateMachineSnapshot.LayerState("upper_body", "none", 0, null, 0f)
        ))));

        Layer<Object> loco = b.layer("locomotion");
        assertEquals("idle", loco.active().id());
        assertEquals("idle_to_walk", loco.activeTransition().id());
        assertEquals(2, loco.stateElapsedTicks());
        assertEquals(0.1f, loco.transitionElapsed(), 1e-4f);

        // 0.25s fade at 20 tps: 0.1 + 3×0.05 = 0.25 → completes on the third tick
        for (int i = 0; i < 5 && !"walk".equals(loco.active().id()); i++) {
            b.tick();
        }
        assertEquals("walk", loco.active().id());
        assertNull(loco.activeTransition());
    }

    @Test
    void unknownTransitionFallsBackToHardSwitch() {
        StateMachine<Object> b = machine();
        b.conform(new StateMachineSnapshot(5, List.of(
                new StateMachineSnapshot.LayerState("locomotion", "walk", 0, "ghost_transition", 0.1f)
        )));
        Layer<Object> loco = b.layer("locomotion");
        assertEquals("walk", loco.active().id());
        assertNull(loco.activeTransition());
    }

    @Test
    void unknownLayerIdsAreIgnoredWithoutVersionBump() {
        StateMachine<Object> b = machine();
        int v0 = b.version();
        assertFalse(b.conform(new StateMachineSnapshot(1, List.of(
                new StateMachineSnapshot.LayerState("unknown_layer", "whatever", 3, null, 0f)
        ))));
        assertEquals(v0, b.version());
        assertEquals("idle", b.layer("locomotion").active().id());
    }

    @Test
    void nullSnapshotIsIgnored() {
        StateMachine<Object> b = machine();
        assertFalse(b.conform((StateMachineSnapshot) null));
        assertEquals(0, b.version());
    }
}
