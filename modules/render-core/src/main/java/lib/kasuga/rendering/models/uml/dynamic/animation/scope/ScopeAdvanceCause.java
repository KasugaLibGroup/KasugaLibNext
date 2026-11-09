package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Reason a host asked an animation scope to advance. */
public enum ScopeAdvanceCause {
    GAME_TICK,
    HOST_SYNC,
    MANUAL,
    PAUSE,
    RESUME,
    SEEK
}
