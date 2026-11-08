package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackRegistryTest {

    private static final UUID SCOPE_ID = UUID.fromString("7f5f3fe8-d6d6-4f50-bab5-e44541539376");

    @Test
    void allocatesMonotonicScopeLocalIdsForRootsAndChildren() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);

        PlaybackInstanceId first = registry.addRoot("a", binding(1.0));
        PlaybackInstanceId second = registry.addRoot("b", binding(1.0));
        PlaybackInstanceId child = registry.addChild(first, "c", binding(1.0));

        assertEquals(SCOPE_ID, first.authorityScopeId());
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(3, child.sequence());
        assertEquals(List.of(first, second, child),
                registry.snapshot().instances().stream().map(PlaybackInstanceState::id).toList());
    }

    @Test
    void stopPauseResumeAndReplaceOperateOnTheWholeSubtree() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId root = registry.addRoot("a", binding(1.0));
        PlaybackInstanceId child = registry.addChild(root, "b", binding(1.0));
        PlaybackInstanceId grandchild = registry.addChild(child, "c", binding(1.0));

        registry.pauseSubtree(root);
        assertTrue(registry.state(root).paused());
        assertTrue(registry.state(child).paused());
        assertTrue(registry.state(grandchild).paused());

        registry.resumeSubtree(root);
        assertFalse(registry.state(root).paused());
        assertFalse(registry.state(child).paused());
        assertFalse(registry.state(grandchild).paused());

        registry.replace(child, "replacement");
        assertEquals("replacement", registry.state(child).definitionId());

        registry.stopSubtree(child);

        assertTrue(registry.state(root).definitionId().equals("a"));
        assertThrows(IllegalArgumentException.class, () -> registry.state(child));
        assertThrows(IllegalArgumentException.class, () -> registry.state(grandchild));
    }

    @Test
    void speedAndBoundaryChangesPreserveThePlacementAndSourceRanges() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId id = registry.addRoot(
                "clip",
                new PlaybackBinding(2.0, 3.0, 1.0, 4.0, 1.0, PlaybackBoundaryPolicy.STOP));

        registry.setSpeed(id, 2.5);
        registry.setBoundaryPolicy(id, PlaybackBoundaryPolicy.PING_PONG);

        PlaybackBinding changed = registry.state(id).binding();
        assertEquals(2.0, changed.placementStart(), 1e-9);
        assertEquals(3.0, changed.placementDuration(), 1e-9);
        assertEquals(1.0, changed.sourceStart(), 1e-9);
        assertEquals(4.0, changed.sourceDuration(), 1e-9);
        assertEquals(2.5, changed.speed(), 1e-9);
        assertEquals(PlaybackBoundaryPolicy.PING_PONG, changed.boundaryPolicy());
    }

    @Test
    void unknownInstancesAreRejected() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId unknown = new PlaybackInstanceId(SCOPE_ID, 99);

        assertThrows(IllegalArgumentException.class,
                () -> registry.addChild(unknown, "child", binding(1.0)));
        assertThrows(IllegalArgumentException.class, () -> registry.stopSubtree(unknown));
        assertThrows(IllegalArgumentException.class, () -> registry.pauseSubtree(unknown));
        assertThrows(IllegalArgumentException.class, () -> registry.resumeSubtree(unknown));
        assertThrows(IllegalArgumentException.class, () -> registry.setSpeed(unknown, 2.0));
        assertThrows(IllegalArgumentException.class,
                () -> registry.setBoundaryPolicy(unknown, PlaybackBoundaryPolicy.REPEAT));
        assertThrows(IllegalArgumentException.class, () -> registry.replace(unknown, "other"));
    }

    @Test
    void viewsAreReadOnlyAndReflectLifecycleChanges() {
        PlaybackRegistry registry = new PlaybackRegistry(SCOPE_ID);
        PlaybackInstanceId root = registry.addRoot("a", binding(1.0));
        PlaybackInstanceId child = registry.addChild(root, "b", binding(2.0));

        List<PlaybackView> views = registry.playbacks();
        PlaybackView childView = views.stream()
                .filter(view -> view.id().equals(child))
                .findFirst()
                .orElseThrow();

        assertEquals(root, childView.parentId());
        assertEquals(0.0, childView.localTime(), 1e-9);
        assertEquals(2.0, childView.speed(), 1e-9);
        assertThrows(UnsupportedOperationException.class, () -> views.add(childView));
    }

    @Test
    void snapshotsRejectDuplicatePlaybackIdentities() {
        PlaybackInstanceState state = new PlaybackInstanceState(
                new PlaybackInstanceId(SCOPE_ID, 1),
                "a",
                Optional.empty(),
                binding(1.0),
                false);

        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackRegistrySnapshot(List.of(state, state)));
    }

    private static PlaybackBinding binding(double speed) {
        return new PlaybackBinding(0.0, 10.0, 0.0, 10.0, speed, PlaybackBoundaryPolicy.STOP);
    }
}
