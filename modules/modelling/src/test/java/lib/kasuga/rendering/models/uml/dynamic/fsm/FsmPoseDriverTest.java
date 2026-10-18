package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link FsmPoseDriver}: {@code tick} (main thread) advances the machine + publishes a {@link PoseTarget};
 * {@code sample} (render thread) composes the pose at frame rate, interpolating the cross-fade by
 * {@code partialTick}.
 */
class FsmPoseDriverTest {

    private static final float DT = 1f / 20f;

    /** idle→active cross-fade over exactly one tick, posing morph "m" 0→1. */
    private static StateMachine<Object> machine() {
        return StateMachine.<Object>builder(new Object())
                .layer("loco", layer -> {
                    State<Object> idle = layer.state("idle").morph("m", 0f);
                    State<Object> active = layer.state("active").morph("m", 1f);
                    layer.initial(idle);
                    layer.transition("to_active", idle, active).when(ctx -> true).crossFade(DT);
                })
                .build();
    }

    private static float morphValue(Blender blender, Object id) {
        Blender.MorphAccum accum = blender.morphs().get(id);
        return accum != null ? accum.value() : Float.NaN;
    }

    @Test
    void noTargetBeforeFirstTick() {
        FsmPoseDriver driver = new FsmPoseDriver(machine(), ModelInstanceFixture.minimal());
        assertNull(driver.currentTarget(), "no target until the first tick publishes one");
        driver.sample(0.5f); // must not throw, must no-op
    }

    @Test
    void tickPublishesTargetWithTransitionInFlight() {
        StateMachine<Object> machine = machine();
        FsmPoseDriver driver = new FsmPoseDriver(machine, ModelInstanceFixture.minimal());

        driver.tick(DT); // idle→active fires (when=true); cross-fade in flight, elapsed=0

        PoseTarget target = driver.currentTarget();
        assertNotNull(target);
        assertEquals(1, target.layers().size());
        PoseTarget.LayerTarget layer = target.layers().get(0);
        assertEquals("idle", layer.activeState().id(), "active stays on `from` while the cross-fade is in flight");
        assertNotNull(layer.activeTransition(), "the in-flight transition must be published");
        assertEquals(0f, layer.transitionElapsed(), 1e-6f);
    }

    @Test
    void sampleInterpolatesCrossFadeByPartialTick() {
        FsmPoseDriver driver = new FsmPoseDriver(machine(), ModelInstanceFixture.minimal());
        driver.tick(DT); // cross-fade in flight: elapsed=0, crossFade=DT → alpha == partialTick

        Blender mid = new Blender();
        // partialTick sweeps the full blend across one frame batch (snapshot.elapsed=0, crossFade=DT)
        driver.compose(mid, driver.currentTarget(), 0f);
        assertEquals(0f, morphValue(mid, "m"), 1e-4f, "partialTick=0 → from pose (m=0)");

        driver.compose(mid, driver.currentTarget(), 0.5f);
        assertEquals(0.5f, morphValue(mid, "m"), 1e-4f, "partialTick=0.5 → midpoint (m=0.5)");

        driver.compose(mid, driver.currentTarget(), 1f);
        assertEquals(1f, morphValue(mid, "m"), 1e-4f, "partialTick=1 → to pose (m=1)");
    }

    @Test
    void sampleDoesNotAdvancePastTransitionCompletion() {
        // After the cross-fade completes (next tick), active=active, no transition; pose is static regardless of partialTick.
        StateMachine<Object> machine = machine();
        FsmPoseDriver driver = new FsmPoseDriver(machine, ModelInstanceFixture.minimal());
        driver.tick(DT); // fire (elapsed=0)
        driver.tick(DT); // elapsed+=DT → completes → active=active

        PoseTarget.LayerTarget layer = driver.currentTarget().layers().get(0);
        assertEquals("active", layer.activeState().id());
        assertNull(layer.activeTransition(), "transition completed; none in flight");

        Blender b = new Blender();
        driver.compose(b, driver.currentTarget(), 0f);
        assertEquals(1f, morphValue(b, "m"), 1e-4f, "completed state holds the active pose independent of partialTick");
        driver.compose(b, driver.currentTarget(), 1f);
        assertEquals(1f, morphValue(b, "m"), 1e-4f);
    }

    @Test
    void rebindKeepsMachineAndTarget() {
        StateMachine<Object> machine = machine();
        ModelInstance first = ModelInstanceFixture.minimal();
        ModelInstance second = ModelInstanceFixture.minimal();
        FsmPoseDriver driver = new FsmPoseDriver(machine, first);
        driver.tick(DT);

        driver.rebind(second); // resource-reload rebind: only the sink target swaps

        assertSame(machine, driver.machine(), "rebind must NOT rebuild the machine");
        assertSame(second, driver.model(), "rebind must re-target the model");
        assertNotNull(driver.currentTarget(), "the published target survives a rebind");
        // sampling after rebind must still compose off the surviving target
        Blender b = new Blender();
        driver.compose(b, driver.currentTarget(), 0.5f);
        assertEquals(0.5f, morphValue(b, "m"), 1e-4f);
    }
}
