package lib.kasuga.rendering.models.uml.dynamic.fsm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 regressions from doc/fsm-design-review.md (block 6.1 + 7.1):
 * <ul>
 *   <li>cross-fade start must bump {@link StateMachine#version()} (so {@code FsmSyncDedup} forwards the
 *       in-flight transition);</li>
 *   <li>the sink must be flushed every tick even when the {@link Blender} is empty, so a sink that
 *       neutralizes dropped channels (e.g. {@link ModelInstancePoseSink}) can clear stale state.</li>
 * </ul>
 */
class MachineTickRegressionTest {

    /** idle (duration 1) --whenComplete/crossFade--> walk: the cross-fade start tick bumps version. */
    @Test
    void crossFadeStartBumpsVersion() {
        StateMachine<Object> m = StateMachine.builder(new Object())
                .layer("l", layer -> {
                    State<Object> idle = layer.state("idle").durationTicks(1);
                    State<Object> walk = layer.state("walk");
                    layer.initial(idle);
                    layer.transition("i2w", idle, walk).whenComplete().crossFade(0.25f);
                })
                .build();

        assertEquals(0, m.version());
        m.tick(); // idle elapsed 0→1, not yet complete
        assertEquals(0, m.version(), "no transition yet — version must not bump");
        m.tick(); // idle complete → cross-fade starts (activeChanged=true)
        assertTrue(m.version() > 0, "cross-fade start must bump version so sync forwards the in-flight transition");
    }

    /** An empty-pose state yields an empty Blender; the sink must still be flushed (residue-reset path). */
    @Test
    void sinkFlushedEvenWhenBlenderEmpty() {
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> m = StateMachine.builder(new Object())
                .sink(sink)
                .layer("l", layer -> {
                    State<Object> idle = layer.state("idle"); // empty pose
                    layer.initial(idle);
                })
                .build();

        assertEquals(0, sink.applyCount);
        m.tick();
        assertTrue(sink.applyCount > 0, "sink must be flushed even when the blender is empty so it can reset stale channels");
    }

    private static final class RecordingSink implements PoseSink {
        int applyCount;

        @Override
        public void apply(Blender blender) {
            applyCount++;
        }
    }

    /**
     * Puppet mode ({@code logicEnabled=false}): a client machine must NOT evaluate transitions / bump version
     * / increment stateElapsedTicks, but MUST advance cross-fade interpolation so the client smooth-blends
     * between server snapshots (conformed via {@link StateMachine#conform(StateMachineSnapshot)}).
     */
    @Test
    void puppetAdvancesCrossFadeWithoutEvaluatingOrBumpingVersion() {
        StateMachine<Object> m = StateMachine.builder(new Object())
                .layer("l", layer -> {
                    State<Object> idle = layer.state("idle").durationTicks(1);
                    State<Object> walk = layer.state("walk");
                    layer.initial(idle);
                    layer.transition("i2w", idle, walk).whenComplete().crossFade(0.25f);
                })
                .build();
        m.setLogicEnabled(false);

        // conform to an in-flight cross-fade (server-authoritative snapshot)
        assertTrue(m.conform(new StateMachineSnapshot(m.version(), List.of(
                new StateMachineSnapshot.LayerState("l", "idle", 0, "i2w", 0f)))));
        assertEquals(0f, m.layer("l").transitionElapsed(), 1e-6f);
        assertEquals(0, m.layer("l").stateElapsedTicks());

        int v0 = m.version();
        m.tick(0.05f);
        assertEquals(0.05f, m.layer("l").transitionElapsed(), 1e-6f, "puppet must advance cross-fade interpolation");
        assertEquals(v0, m.version(), "puppet must not bump version");
        assertEquals(0, m.layer("l").stateElapsedTicks(), "puppet must not increment stateElapsedTicks");
    }
}
