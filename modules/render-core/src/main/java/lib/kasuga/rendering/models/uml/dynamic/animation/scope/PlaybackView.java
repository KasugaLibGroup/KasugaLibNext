package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Read-only playback state exposed to controllers.
 *
 * <p>{@code paused} is an explicit control state. {@code active} and {@code completed} describe
 * the playback instance's placement/source window and must not be inferred from {@code paused}.
 */
public record PlaybackView(
        PlaybackInstanceId id,
        String definitionId,
        PlaybackInstanceId parentId,
        double localTime,
        double speed,
        boolean paused,
        boolean active,
        boolean completed
) {

    /** Source-compatible constructor for callers that only need lifecycle-neutral metadata. */
    public PlaybackView(
            PlaybackInstanceId id,
            String definitionId,
            PlaybackInstanceId parentId,
            double localTime,
            double speed,
            boolean paused
    ) {
        this(id, definitionId, parentId, localTime, speed, paused, !paused, false);
    }
}
