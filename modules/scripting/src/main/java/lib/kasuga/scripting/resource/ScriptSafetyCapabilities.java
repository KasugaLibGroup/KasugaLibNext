package lib.kasuga.scripting.resource;

/** Evidence must cover the engine and every transitively exposed host object. */
public record ScriptSafetyCapabilities(boolean hostIsolation, boolean fileIsolation, boolean networkIsolation,
                                        boolean boundedExecution, boolean boundedMemory, boolean cancellable) {
    public static final ScriptSafetyCapabilities UNVERIFIED = new ScriptSafetyCapabilities(false, false, false, false, false, false);
    public boolean supportsUntrusted() { return hostIsolation && fileIsolation && networkIsolation && boundedExecution && boundedMemory && cancellable; }
}
