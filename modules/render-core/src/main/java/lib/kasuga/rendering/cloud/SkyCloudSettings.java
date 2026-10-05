package lib.kasuga.rendering.cloud;

import java.util.Objects;
import java.util.List;

/** World units and absolute layer altitudes. The nine-argument constructor derives three layers;
 * maxHeight also controls embedded cumulonimbus growth. */
public record SkyCloudSettings(int seed, float cellSize, float baseHeight, float maxHeight,
                               float coverage, float distance, float windX, float windZ, Quality quality,
                               List<SkyCloudLayer> layers) {
    public static final SkyCloudSettings DEFAULT = new SkyCloudSettings(124, 2400, 900, 3000,
            .60f, 30000, 8, 3, Quality.BALANCED);
    public static final SkyCloudSettings SCATTERED = DEFAULT.withCoverage(.38f);
    public static final SkyCloudSettings CLOUDY = DEFAULT.withCoverage(.80f);
    public static final SkyCloudSettings OVERCAST = DEFAULT.withCoverage(.98f);

    public SkyCloudSettings(int seed, float cellSize, float baseHeight, float maxHeight, float coverage,
                            float distance, float windX, float windZ, Quality quality) {
        this(seed, cellSize, baseHeight, maxHeight, coverage, distance, windX, windZ, quality,
                List.of(new SkyCloudLayer(SkyCloudLayer.Type.CUMULUS, baseHeight, Math.max(32, maxHeight * .7f), 5200, 0),
                        new SkyCloudLayer(SkyCloudLayer.Type.STRATIFORM, baseHeight + maxHeight * .6f, Math.max(32, maxHeight * .3f), 3400, -.08f),
                        new SkyCloudLayer(SkyCloudLayer.Type.CIRRUS, baseHeight + maxHeight * 1.5f, Math.max(32, maxHeight * .2f), 8000, -.12f)));
    }

    public SkyCloudSettings {
        Objects.requireNonNull(quality);
        layers = List.copyOf(layers);
        if (layers.isEmpty() || layers.size() > 4) throw new IllegalArgumentException("Cloud field needs 1..4 layers");
        for (float value : new float[]{cellSize, baseHeight, maxHeight, coverage, distance, windX, windZ})
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Cloud settings must be finite");
        if (cellSize < 128 || cellSize > 10000 || maxHeight < 64 || maxHeight > 10000
                || coverage < 0 || coverage > 1 || distance < cellSize || distance > cellSize * 14
                || Math.abs(baseHeight) > 100000 || Math.abs(windX) > 1000 || Math.abs(windZ) > 1000)
            throw new IllegalArgumentException("Cloud field settings outside supported range");
    }
    public SkyCloudSettings withCoverage(float value) {
        return new SkyCloudSettings(seed, cellSize, baseHeight, maxHeight, value, distance, windX, windZ, quality, layers);
    }
    public SkyCloudSettings withQuality(Quality value) {
        return new SkyCloudSettings(seed, cellSize, baseHeight, maxHeight, coverage, distance, windX, windZ, value, layers);
    }
    public SkyCloudSettings withSeed(int value) {
        return new SkyCloudSettings(value, cellSize, baseHeight, maxHeight, coverage, distance, windX, windZ, quality, layers);
    }
    public SkyCloudSettings withLayers(List<SkyCloudLayer> value) {
        return new SkyCloudSettings(seed, cellSize, baseHeight, maxHeight, coverage, distance, windX, windZ, quality, value);
    }
    public enum Quality {
        FAST(3, 112, 1), BALANCED(2, 160, 2), HIGH(1, 256, 3);
        public final int resolutionDivisor, sampleBudget, lightSteps;
        Quality(int divisor, int budget, int lighting) {
            resolutionDivisor = divisor; sampleBudget = budget; lightSteps = lighting;
        }
    }
}
