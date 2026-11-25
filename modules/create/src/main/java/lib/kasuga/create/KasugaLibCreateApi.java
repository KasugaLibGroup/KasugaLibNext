package lib.kasuga.create;

/**
 * Public API for mods integrating with the Create module.
 */
public final class KasugaLibCreateApi {

    private static volatile boolean multiBogeyCarriageDefault = false;

    private static volatile boolean carriageYOffsetDefault = false;

    private static volatile boolean derailedOnBogeyOffsetDefault = false;

    private static volatile boolean contraptionWeatherFilterDefault = true;

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

    public static void setCarriageYOffsetDefault(boolean enabled) {
        carriageYOffsetDefault = enabled;
    }

    public static void setDerailedOnBogeyOffsetDefault(boolean enabled) {
        derailedOnBogeyOffsetDefault = enabled;
    }

    /**
     * Overrides the default of the {@code enable_contraption_weather_filter} common config entry.
     * Call during mod construction, before configs load. An explicit config value takes priority.
     */
    public static void setContraptionWeatherFilterDefault(boolean enabled) {
        contraptionWeatherFilterDefault = enabled;
    }

    /** Current default used when the config entry is absent. */
    public static boolean getMultiBogeyCarriageDefault() {
        return multiBogeyCarriageDefault;
    }

    public static boolean getCarriageYOffsetDefault() {return carriageYOffsetDefault;}

    public static boolean getDerailedOnBogeyOffsetDefault() { return derailedOnBogeyOffsetDefault; }

    public static boolean getContraptionWeatherFilterDefault() {
        return contraptionWeatherFilterDefault;
    }
}
