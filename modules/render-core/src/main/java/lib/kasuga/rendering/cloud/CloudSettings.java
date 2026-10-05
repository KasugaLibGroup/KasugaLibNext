package lib.kasuga.rendering.cloud;

/** Optical and morphological controls for a cumulonimbus volume. Extinction is per world unit. */
public record CloudSettings(float density, float extinction, float erosion, float coverage, float anvil, int seed, Quality quality) {
    public static final CloudSettings CUMULONIMBUS = new CloudSettings(1, .01f, .75f, .90f, 1, 124, Quality.BALANCED);

    public CloudSettings(float density, float extinction, float erosion, float anvil, int seed, Quality quality) {
        this(density, extinction, erosion, .85f, anvil, seed, quality);
    }

    public CloudSettings {
        range(density, 0, 4, "density");
        range(extinction, .0001f, 1, "extinction");
        range(erosion, 0, 1, "erosion");
        range(coverage, 0, 1, "coverage");
        range(anvil, 0, 1, "anvil");
        java.util.Objects.requireNonNull(quality, "quality");
    }

    public CloudSettings withDensity(float value) { return new CloudSettings(value, extinction, erosion, coverage, anvil, seed, quality); }
    public CloudSettings withCoverage(float value) { return new CloudSettings(density, extinction, erosion, value, anvil, seed, quality); }
    public CloudSettings withQuality(Quality value) { return new CloudSettings(density, extinction, erosion, coverage, anvil, seed, value); }

    static void range(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
    }

    public enum Quality {
        FAST(48, 4, 3, 24), BALANCED(80, 6, 2, 32), HIGH(128, 8, 1, 48);
        public final int viewSteps, lightSteps, resolutionDivisor, lightGridSize;
        Quality(int viewSteps, int lightSteps, int resolutionDivisor, int lightGridSize) {
            this.viewSteps = viewSteps; this.lightSteps = lightSteps;
            this.resolutionDivisor = resolutionDivisor; this.lightGridSize = lightGridSize;
        }
    }
}
