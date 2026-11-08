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

    void replace(PlaybackInstanceId id, String definitionId);

    PlaybackInstanceState state(PlaybackInstanceId id);

    List<PlaybackView> views();
}
