package lib.kasuga.create;

/**
 * Public API for mods integrating with the Create module.
 */
public final class KasugaLibCreateApi {

    private static volatile boolean multiBogeyCarriageDefault = false;

    private KasugaLibCreateApi() {
    }

    /**
     * Overrides the generated default of the {@code enable_multi_bogey_carriage} common config
     * entry. Only the default is affected: a value already present in the config file still wins.
     * Call during mod construction, before configs are loaded.
     */
    public static void setMultiBogeyCarriageDefault(boolean enabled) {
        multiBogeyCarriageDefault = enabled;
    }

    /** Current default used when the config entry is absent. */
    public static boolean getMultiBogeyCarriageDefault() {
        return multiBogeyCarriageDefault;
    }
}
