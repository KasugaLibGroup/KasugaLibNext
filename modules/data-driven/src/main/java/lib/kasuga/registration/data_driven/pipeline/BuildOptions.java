package lib.kasuga.registration.data_driven.pipeline;

/**
 * Policy for one multi-source data-driven build session.
 */
public enum BuildOptions {
    /**
     * Preserve the historical tolerant loader behavior.
     */
    LEGACY,

    /**
     * Reject the whole session before apply when structure, references, or registry claims are unsafe.
     */
    STRICT;

    public boolean isStrict() {
        return this == STRICT;
    }
}
