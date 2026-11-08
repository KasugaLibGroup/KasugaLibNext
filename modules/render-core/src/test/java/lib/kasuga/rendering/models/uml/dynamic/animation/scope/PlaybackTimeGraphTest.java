package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackTimeGraphTest {

    private static final UUID SCOPE_ID = UUID.fromString("2f562f8c-44eb-43bf-a5b8-c16fcc8d0c90");

    @Test
    void playbackBindingValidatesItsIndependentRangesAndSpeed() {
        PlaybackBinding binding = new PlaybackBinding(
                1.0, 2.0, 0.5, 4.0, 1.5, PlaybackBoundaryPolicy.REPEAT);

        assertEquals(3.0, binding.placementEnd(), 1e-9);
        assertEquals(4.5, binding.sourceEnd(), 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackBinding(-1.0, 2.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackBinding(0.0, 0.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackBinding(0.0, 1.0, 0.0, 0.0, 1.0, PlaybackBoundaryPolicy.STOP));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackBinding(0.0, 1.0, 0.0, 1.0, -0.1, PlaybackBoundaryPolicy.STOP));
    }

    @Test
    void nestedInstancesDeriveEveryLocalTimeFromTheParentSourceTime() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId sequence = registry.addRoot(
                "sequence/b",
                new PlaybackBinding(0.0, 10.0, 0.0, 10.0, 1.0, PlaybackBoundaryPolicy.STOP));
        PlaybackInstanceId clip = registry.addChild(
                sequence,
                "clip/c",
                new PlaybackBinding(2.0, 2.0, 0.0, 2.0, 2.0, PlaybackBoundaryPolicy.STOP));

        List<PlaybackTimeResolution> resolutions =
                new PlaybackTimeGraph().resolve(registry.snapshot(), 2.0, 2.5);

        PlaybackTimeResolution b = resolution(resolutions, sequence);
        PlaybackTimeResolution c = resolution(resolutions, clip);
        assertTrue(b.active());
        assertTrue(c.active());
        assertEquals(2.0, c.previousParentLocalTime(), 1e-9);
        assertEquals(2.5, c.currentParentLocalTime(), 1e-9);
        assertEquals(0.0, c.previousSourceLocalTime(), 1e-9);
        assertEquals(1.0, c.currentSourceLocalTime(), 1e-9);
        assertEquals(0.0, c.previousUnwrappedSourceTime(), 1e-9);
        assertEquals(1.0, c.currentUnwrappedSourceTime(), 1e-9);
    }

    @Test
    void aChildIsInactiveWhenItsPlacementWindowDoesNotContainTheStep() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId sequence = registry.addRoot(
                "sequence/b",
                new PlaybackBinding(0.0, 10.0, 0.0, 10.0, 1.0, PlaybackBoundaryPolicy.STOP));
        PlaybackInstanceId lateClip = registry.addChild(
                sequence,
                "clip/c",
                new PlaybackBinding(5.0, 1.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP));

        List<PlaybackTimeResolution> resolutions =
                new PlaybackTimeGraph().resolve(registry.snapshot(), 1.0, 2.0);

        assertTrue(resolution(resolutions, sequence).active());
        PlaybackTimeResolution child = resolution(resolutions, lateClip);
        assertFalse(child.active());
        assertFalse(child.completed());
        assertEquals(1.0, child.previousParentLocalTime(), 1e-9);
        assertEquals(2.0, child.currentParentLocalTime(), 1e-9);
    }

    @Test
    void anInactiveParentMakesItsWholeSubtreeInactive() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId parent = registry.addRoot(
                "sequence/b",
                new PlaybackBinding(0.0, 1.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP));
        PlaybackInstanceId child = registry.addChild(
                parent,
                "clip/c",
                new PlaybackBinding(0.0, 1.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP));

        List<PlaybackTimeResolution> resolutions =
                new PlaybackTimeGraph().resolve(registry.snapshot(), 1.0, 2.0);

        assertFalse(resolution(resolutions, parent).active());
        assertTrue(resolution(resolutions, parent).completed());
        assertFalse(resolution(resolutions, child).active());
    }

    @Test
    void stopAndHoldApplyDifferentSourceEndBehavior() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId stop = registry.addRoot(
                "clip/stop",
                new PlaybackBinding(0.0, 2.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP));
        PlaybackInstanceId hold = registry.addRoot(
                "clip/hold",
                new PlaybackBinding(0.0, 2.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.HOLD_LAST));

        List<PlaybackTimeResolution> resolutions =
                new PlaybackTimeGraph().resolve(registry.snapshot(), 0.5, 1.5);

        PlaybackTimeResolution stopped = resolution(resolutions, stop);
        PlaybackTimeResolution held = resolution(resolutions, hold);
        assertFalse(stopped.active());
        assertTrue(stopped.completed());
        assertTrue(held.active());
        assertFalse(held.completed());
        assertEquals(1.0, held.currentSourceLocalTime(), 1e-9);
    }

    @Test
    void repeatWrapsAndPingPongReversesUsingUnwrappedTime() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId repeat = registry.addRoot(
                "clip/repeat",
                new PlaybackBinding(0.0, 4.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.REPEAT));
        PlaybackInstanceId pingPong = registry.addRoot(
                "clip/ping_pong",
                new PlaybackBinding(0.0, 4.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.PING_PONG));

        List<PlaybackTimeResolution> resolutions =
                new PlaybackTimeGraph().resolve(registry.snapshot(), 0.75, 1.25);

        PlaybackTimeResolution repeated = resolution(resolutions, repeat);
        assertEquals(0.75, repeated.previousSourceLocalTime(), 1e-9);
        assertEquals(0.25, repeated.currentSourceLocalTime(), 1e-9);
        assertEquals(1.25, repeated.currentUnwrappedSourceTime(), 1e-9);
        assertEquals(1, repeated.loopCrossings());

        PlaybackTimeResolution bounced = resolution(resolutions, pingPong);
        assertEquals(0.75, bounced.previousSourceLocalTime(), 1e-9);
        assertEquals(0.75, bounced.currentSourceLocalTime(), 1e-9);
        assertEquals(1, bounced.loopCrossings());
        assertEquals(-1, bounced.direction());
    }

    @Test
    void aMalformedParentCycleIsRejected() {
        PlaybackInstanceId first = new PlaybackInstanceId(SCOPE_ID, 1);
        PlaybackInstanceId second = new PlaybackInstanceId(SCOPE_ID, 2);
        PlaybackBinding binding = new PlaybackBinding(
                0.0, 1.0, 0.0, 1.0, 1.0, PlaybackBoundaryPolicy.STOP);
        PlaybackRegistrySnapshot snapshot = new PlaybackRegistrySnapshot(List.of(
                new PlaybackInstanceState(first, "a", Optional.of(second), binding, false),
                new PlaybackInstanceState(second, "b", Optional.of(first), binding, false)
        ));

        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackTimeGraph().resolve(snapshot, 0.0, 0.5));
    }

    private static PlaybackTimeResolution resolution(
            List<PlaybackTimeResolution> resolutions,
            PlaybackInstanceId id
    ) {
        return resolutions.stream()
                .filter(candidate -> candidate.instanceId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
