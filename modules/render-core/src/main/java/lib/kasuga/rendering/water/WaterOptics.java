package lib.kasuga.rendering.water;

/** Per-surface light absorption, in-scattering, and restrained near-shore foam. */
public record WaterOptics(float absorptionRed, float absorptionGreen, float absorptionBlue,
                          float scatterRed, float scatterGreen, float scatterBlue, float shorelineFoam) {
    public static final WaterOptics DEFAULT = new WaterOptics(3.6f, 1.6f, .24f, .008f, .075f, .46f, 0);
    public static final WaterOptics CLEAR_COAST = new WaterOptics(.42f, .20f, .075f, .018f, .085f, .22f, .24f);

    public WaterOptics {
        check(absorptionRed, 20, "absorptionRed");
        check(absorptionGreen, 20, "absorptionGreen");
        check(absorptionBlue, 20, "absorptionBlue");
        check(scatterRed, 1, "scatterRed");
        check(scatterGreen, 1, "scatterGreen");
        check(scatterBlue, 1, "scatterBlue");
        check(shorelineFoam, 1, "shorelineFoam");
    }

    private static void check(float value, float maximum, String name) {
        if (!Float.isFinite(value) || value < 0 || value > maximum)
            throw new IllegalArgumentException(name + " must be finite and in [0, " + maximum + "]");
    }
}
