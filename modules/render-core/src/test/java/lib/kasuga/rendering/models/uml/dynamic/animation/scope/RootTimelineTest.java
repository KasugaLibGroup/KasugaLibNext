package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootTimelineTest {

    @Test
    void advancesWithTheInjectedFixedStep() {
        RootTimeline timeline = new RootTimeline(LogicalStepPolicy.fixed(0.05, 4));

        RootTimelinePlan plan = timeline.planAdvance(new ScopeAdvance(0.12, ScopeAdvanceCause.GAME_TICK));

        assertEquals(2, plan.steps().size());
        assertStep(plan.steps().get(0), 0.0, 0.05, 0.05);
        assertStep(plan.steps().get(1), 0.05, 0.10, 0.05);
        assertEquals(0.02, plan.snapshot().accumulatorSeconds(), 1e-9);

        timeline.commit(plan);

        assertEquals(0.10, timeline.snapshot().currentTime(), 1e-9);
        assertEquals(0.05, timeline.snapshot().previousTime(), 1e-9);
    }

    @Test
    void pauseAndResumePublishControlStepsAndFreezeTime() {
        RootTimeline timeline = new RootTimeline(LogicalStepPolicy.fixed(0.05, 4));
        timeline.commit(timeline.planAdvance(new ScopeAdvance(0.10, ScopeAdvanceCause.GAME_TICK)));

        RootTimelinePlan pause = timeline.planPause();
        assertEquals(1, pause.steps().size());
        assertEquals(ScopeAdvanceCause.PAUSE, pause.steps().getFirst().cause());
        timeline.commit(pause);

        RootTimelinePlan frozen = timeline.planAdvance(new ScopeAdvance(1.0, ScopeAdvanceCause.GAME_TICK));
        assertTrue(frozen.steps().isEmpty());
        assertEquals(0.10, frozen.snapshot().currentTime(), 1e-9);

        RootTimelinePlan resume = timeline.planResume();
        assertEquals(1, resume.steps().size());
        assertEquals(ScopeAdvanceCause.RESUME, resume.steps().getFirst().cause());
        timeline.commit(resume);

        assertFalse(timeline.snapshot().paused());
        timeline.commit(timeline.planAdvance(new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK)));
        assertEquals(0.15, timeline.snapshot().currentTime(), 1e-9);
    }

    @Test
    void seekCanMoveBackwardAndMarksDiscontinuity() {
        RootTimeline timeline = new RootTimeline(LogicalStepPolicy.fixed(0.05, 4));
        timeline.commit(timeline.planAdvance(new ScopeAdvance(0.20, ScopeAdvanceCause.GAME_TICK)));

        RootTimelinePlan seek = timeline.planSeek(0.05);

        assertEquals(1, seek.steps().size());
        TemporalStep step = seek.steps().getFirst();
        assertEquals(0.20, step.previousTime(), 1e-9);
        assertEquals(0.05, step.currentTime(), 1e-9);
        assertEquals(ScopeAdvanceCause.SEEK, step.cause());
        assertEquals(TemporalDiscontinuity.SEEK, step.discontinuity());

        timeline.commit(seek);

        assertEquals(0.05, timeline.snapshot().currentTime(), 1e-9);
        assertEquals(0.0, timeline.snapshot().accumulatorSeconds(), 1e-9);
    }

    @Test
    void catchUpIsBoundedAndOverrunIsReported() {
        RootTimeline timeline = new RootTimeline(LogicalStepPolicy.fixed(0.05, 2));

        RootTimelinePlan plan = timeline.planAdvance(new ScopeAdvance(1.0, ScopeAdvanceCause.GAME_TICK));

        assertEquals(2, plan.steps().size());
        assertEquals(18, plan.droppedSteps());
        assertEquals(0.0, plan.snapshot().accumulatorSeconds(), 1e-9);
        timeline.commit(plan);
        assertEquals(0.10, timeline.snapshot().currentTime(), 1e-9);
    }

    @Test
    void aTwentyHertzPlatformAndSixtyHertzPlatformUseTheSameTimelineSemantics() {
        List<Double> stepSizes = List.of(1.0 / 20.0, 1.0 / 60.0, 0.10);

        for (double step : stepSizes) {
            RootTimeline timeline = new RootTimeline(LogicalStepPolicy.fixed(step, 4));
            RootTimelinePlan plan = timeline.planAdvance(
                    new ScopeAdvance(step * 3.0, ScopeAdvanceCause.GAME_TICK));

            assertEquals(3, plan.steps().size());
            assertEquals(step, plan.steps().getFirst().deltaSeconds(), 1e-9);
            assertEquals(step * 3.0, plan.steps().getLast().currentTime(), 1e-9);
        }
    }

    private static void assertStep(TemporalStep step, double previous, double current, double delta) {
        assertEquals(previous, step.previousTime(), 1e-9);
        assertEquals(current, step.currentTime(), 1e-9);
        assertEquals(delta, step.deltaSeconds(), 1e-9);
    }
}
