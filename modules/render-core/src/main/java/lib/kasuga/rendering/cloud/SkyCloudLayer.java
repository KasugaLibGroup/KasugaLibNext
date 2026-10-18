package lib.kasuga.rendering.cloud;

import java.util.Objects;

/** Independent altitude/profile for a continuous cloud layer; heights use world units. */
public record SkyCloudLayer(Type type, float baseHeight, float thickness, float detailScale, float coverageBias, float density) {
    public SkyCloudLayer(Type type, float baseHeight, float thickness, float detailScale, float coverageBias) {
        this(type, baseHeight, thickness, detailScale, coverageBias, type == Type.CIRRUS ? .55f : type == Type.CUMULUS ? 2 : 1.5f);
    }
    public SkyCloudLayer {
        Objects.requireNonNull(type);
        for (float value : new float[]{baseHeight, thickness, detailScale, coverageBias, density})
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Cloud layer parameters must be finite");
        if (Math.abs(baseHeight) > 100000 || thickness < 32 || thickness > 20000
                || detailScale < 128 || detailScale > 40000 || Math.abs(coverageBias) > 1 || density < 0 || density > 4)
            throw new IllegalArgumentException("Cloud layer outside supported range");
    }
    public SkyCloudLayer atHeight(float value) { return new SkyCloudLayer(type, value, thickness, detailScale, coverageBias, density); }
    public SkyCloudLayer withDensity(float value) { return new SkyCloudLayer(type, baseHeight, thickness, detailScale, coverageBias, value); }
    public enum Type { CUMULUS, STRATIFORM, CIRRUS }
}
