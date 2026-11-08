package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeSnapshotPublisherTest {

    @Test
    void revisionAdvancesOnlyWhenACompleteLogicalStepIsPublished() {
        ScopeSnapshotPublisher publisher = new ScopeSnapshotPublisher();
        TemporalStep step = new TemporalStep(
                0.0, 0.05, 0.05, ScopeAdvanceCause.GAME_TICK, TemporalDiscontinuity.NONE);

        assertEquals(0, publisher.currentRevision());

        ScopeSnapshot first = publisher.publish(
                step,
                new RootTimelineSnapshot(0.0, 0.05, 0.0, false, false),
                new ScopeInputSnapshot(Map.of(), Set.of()),
                List.of());
        ScopeSnapshot second = publisher.publish(
                step,
                new RootTimelineSnapshot(0.05, 0.10, 0.0, false, false),
                first.inputs(),
                List.of());

        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
        assertEquals(2, publisher.currentRevision());
    }

    @Test
    void aClosedPublisherRejectsMoreSteps() {
        ScopeSnapshotPublisher publisher = new ScopeSnapshotPublisher();
        publisher.close();

        assertThrows(IllegalStateException.class, () -> publisher.publish(
                new TemporalStep(
                        0.0, 0.05, 0.05, ScopeAdvanceCause.GAME_TICK,
                        TemporalDiscontinuity.NONE),
                new RootTimelineSnapshot(0.0, 0.05, 0.0, false, false),
                new ScopeInputSnapshot(Map.of(), Set.of()),
                List.of()));
    }
}
