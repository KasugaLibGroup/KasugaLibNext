package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopAwareSampleTimesTest {

    @Test
    void aSingleStepWithManyLoopCrossingsRemainsBounded() {
        PlaybackInstanceId id = new PlaybackInstanceId(
                UUID.fromString("ebcd8db0-a55e-4bba-b9d5-3d5fb1534cb8"), 1);
        PlaybackTimeResolution resolution = new PlaybackTimeResolution(
                id,
                "tiny",
                Optional.empty(),
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                1.0,
                true,
                100_000,
                1,
                false);
        TemporalStep step = new TemporalStep(
                0.0, 1.0, 1.0,
                ScopeAdvanceCause.GAME_TICK,
                TemporalDiscontinuity.NONE);
        TargetEvaluationContext context = new TargetEvaluationContext(
                1,
                step,
                new RootTimelineSnapshot(0.0, 1.0, 0.0, false, false),
                new ScopeInputSnapshot(java.util.Map.of(), java.util.Set.of()),
                List.of(),
                List.of(resolution));

        List<LoopAwareSampleTimes.SampleTime> samples =
                LoopAwareSampleTimes.forStep(context, 0.000001, true, 0.000001);

        assertEquals(4, samples.size());
        assertEquals(0.0, samples.getFirst().rawTime(), 1e-12);
        assertEquals(0.000001, samples.get(1).rawTime(), 1e-12);
        assertEquals(0.000001, samples.get(2).rawTime(), 1e-12);
        assertEquals(1.0, samples.getLast().rawTime(), 1e-12);
    }
}
