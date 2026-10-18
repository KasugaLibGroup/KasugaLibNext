package lib.kasuga.rendering.effect;

/** Defines how a registry handles an already active resource ID. */
public enum DuplicatePolicy {
    /** Treat duplicate IDs as a programming or ownership error. */
    FAIL,
    /** Atomically replace the current registration. */
    REPLACE
}
