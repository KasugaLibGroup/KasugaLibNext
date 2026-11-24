package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;

/** Narrow host-facing controls for playback instances owned by one scope. */
public interface PlaybackControlPort {

    PlaybackInstanceId addRoot(String definitionId, PlaybackBinding binding);

    PlaybackInstanceId addChild(
            PlaybackInstanceId parentId,
            String definitionId,
            PlaybackBinding binding);

    void stopSubtree(PlaybackInstanceId rootId);

    void pauseSubtree(PlaybackInstanceId rootId);

    void resumeSubtree(PlaybackInstanceId rootId);

    void setSpeed(PlaybackInstanceId id, double speed);

    void setBoundaryPolicy(PlaybackInstanceId id, PlaybackBoundaryPolicy policy);

    default void seek(PlaybackInstanceId id, double sourceTime) {
        throw new UnsupportedOperationException("seek is not supported by this playback port");
    }

    void replace(PlaybackInstanceId id, String definitionId);

    default void replace(
            PlaybackInstanceId id,
            String definitionId,
            PlaybackBinding binding
    ) {
        replace(id, definitionId);
    }

    PlaybackInstanceState state(PlaybackInstanceId id);

    List<PlaybackView> views();

    default PlaybackRegistrySnapshot snapshot() {
        throw new UnsupportedOperationException("playback snapshots are not supported");
    }

    default void restore(PlaybackRegistrySnapshot snapshot) {
        throw new UnsupportedOperationException("playback restore is not supported");
    }
}
