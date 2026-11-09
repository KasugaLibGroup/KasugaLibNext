package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Strategy applied when the source range reaches its end. */
public enum PlaybackBoundaryPolicy {
    STOP,
    HOLD_LAST,
    REPEAT,
    PING_PONG
}
