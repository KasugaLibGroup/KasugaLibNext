package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.UUID;

/** Read-only state emitted by an animation scope. */
public record ScopeView(
        UUID scopeId,
        double previousTime,
        double currentTime,
        long revision,
        boolean paused,
        boolean closed,
        List<PlaybackView> activePlaybacks
) {

    public ScopeView {
        activePlaybacks = List.copyOf(activePlaybacks);
    }

    /** All registered playback views, including waiting, paused and completed instances. */
    public List<PlaybackView> playbackViews() {
        return activePlaybacks;
    }
}
