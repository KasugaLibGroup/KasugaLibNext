package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopedPlaybackSupportTest {

    @Test
    void subStepRemaindersAccumulateInsteadOfBecomingSeeks() {
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.fromString("2bce5bcb-a4a2-4f03-b71b-14902f99619b"),
                        LogicalStepPolicy.fixed(0.05, 4))
                .build();

        ScopedPlaybackSupport.advance(scope, 0.12f);
        assertEquals(0.10, scope.view().currentTime(), 1e-6);
        assertEquals(2, scope.view().revision());

        ScopedPlaybackSupport.advance(scope, 0.031f);
        assertEquals(0.15, scope.view().currentTime(), 1e-6);
        assertEquals(3, scope.view().revision());
    }

    @Test
    void overrunIsDroppedWithoutCreatingADiscontinuousSeek() {
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.fromString("4a359549-85a3-490e-a8d6-6e0b8ec4cc8e"),
                        LogicalStepPolicy.fixed(0.05, 2))
                .build();

        ScopedPlaybackSupport.advance(scope, 1f);

        assertEquals(0.10, scope.view().currentTime(), 1e-6);
        assertEquals(2, scope.view().revision());
    }
}
