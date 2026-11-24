package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedPlaybackCommandApplierTest {

    @Test
    void discardingACommandStageRestoresThePlaybackRegistry() {
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(0.05, 5))
                .build();
        PlaybackBinding binding = new PlaybackBinding(
                0.0, 5.0, 0.0, 5.0, 1.0, PlaybackBoundaryPolicy.HOLD_LAST);
        PlaybackCommandApplier applier = ScopedPlaybackCommandApplier
                .factory(Map.of("clip", binding))
                .create(scope.playbackPort());
        PlaybackCommandApplier.Stage stage = applier.begin();

        CommandApplyResult result = stage.apply(new PlaybackCommand.Play(
                "clip", Optional.empty(), Optional.empty(), 1.0));

        assertTrue(result.assignedInstanceId().isPresent());
        assertEquals(1, scope.playbackPort().views().size());

        stage.discard();

        assertEquals(0, scope.playbackPort().views().size());
        scope.close();
    }
}
