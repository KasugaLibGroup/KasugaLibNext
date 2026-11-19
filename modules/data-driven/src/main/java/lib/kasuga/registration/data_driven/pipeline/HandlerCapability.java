package lib.kasuga.registration.data_driven.pipeline;

/**
 * Capabilities a handler declares before it may participate in a strict session.
 */
public enum HandlerCapability {
    /**
     * The handler performs side-effect-free validation and resolves all required startup references.
     */
    STRICT_PREFLIGHT,

    /**
     * Definitions are startup configuration and may be selected by source priority.
     */
    NON_REGISTRATION,

    /**
     * Definitions occupy Minecraft registry keys and must report every claim before apply.
     */
    REGISTRY_CLAIMS
}
