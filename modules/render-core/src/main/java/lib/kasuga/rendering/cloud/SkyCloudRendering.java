package lib.kasuga.rendering.cloud;

import java.util.Objects;

/** Optical coefficients use world metres (Minecraft blocks). Zero sampling
 * overrides select the quality preset. Spatial reconstruction is independent
 * of history, so detached cameras and discontinuous views remain usable. */
public record SkyCloudRendering(float extinction, float erosion, float detailFrequency,
                                float forwardAnisotropy, float backwardAnisotropy, float backwardWeight,
                                float multipleScattering, float ambientStrength, float atmosphereDistance,
                                int viewSteps, int lightSteps, int resolutionDivisor,
                                boolean adaptive, DebugView debug, ColorSpace colorSpace, SkyCloudTemporal temporal) {
    public static final SkyCloudRendering DEFAULT = new SkyCloudRendering(
            .009f, .12f, 4, .55f, -.25f, .15f, .5f, 1, 22360,
            0, 0, 0, true, DebugView.NONE, ColorSpace.DISPLAY_SRGB);

    public SkyCloudRendering(float extinction, float erosion, float detailFrequency,
                             float forwardAnisotropy, float backwardAnisotropy, float backwardWeight,
                             float multipleScattering, float ambientStrength, float atmosphereDistance,
                             int viewSteps, int lightSteps, int resolutionDivisor, boolean adaptive, DebugView debug, ColorSpace colorSpace) {
        this(extinction, erosion, detailFrequency, forwardAnisotropy, backwardAnisotropy, backwardWeight,
                multipleScattering, ambientStrength, atmosphereDistance, viewSteps, lightSteps, resolutionDivisor,
                adaptive, debug, colorSpace, SkyCloudTemporal.STABLE);
    }

    public SkyCloudRendering {
        Objects.requireNonNull(debug); Objects.requireNonNull(colorSpace); Objects.requireNonNull(temporal);
        for (float v : new float[]{extinction, erosion, detailFrequency, forwardAnisotropy,
                backwardAnisotropy, backwardWeight, multipleScattering, ambientStrength, atmosphereDistance})
            if (!Float.isFinite(v)) throw new IllegalArgumentException("Cloud optics must be finite");
        if (extinction < 0 || extinction > .1f || erosion < 0 || erosion > 1
                || detailFrequency < 1 || detailFrequency > 32
                || forwardAnisotropy < 0 || forwardAnisotropy > .9f
                || backwardAnisotropy < -.9f || backwardAnisotropy > 0
                || backwardWeight < 0 || backwardWeight > 1 || multipleScattering < 0 || multipleScattering > .8f
                || ambientStrength < 0 || ambientStrength > 4 || atmosphereDistance < 100 || atmosphereDistance > 200000
                || (viewSteps != 0 && (viewSteps < 16 || viewSteps > 512))
                || lightSteps < 0 || lightSteps > 8 || resolutionDivisor < 0 || resolutionDivisor > 4)
            throw new IllegalArgumentException("Cloud optics or sampling outside supported range");
    }
    public int viewSteps(SkyCloudSettings.Quality quality) { return viewSteps == 0 ? quality.sampleBudget : viewSteps; }
    public int lightSteps(SkyCloudSettings.Quality quality) { return lightSteps == 0 ? quality.lightSteps : lightSteps; }
    public int resolutionDivisor(SkyCloudSettings.Quality quality) {
        return resolutionDivisor == 0 ? quality.resolutionDivisor : resolutionDivisor;
    }
    public SkyCloudRendering withSampling(int view, int light, int divisor, boolean adaptive) {
        return new SkyCloudRendering(extinction, erosion, detailFrequency, forwardAnisotropy, backwardAnisotropy,
                backwardWeight, multipleScattering, ambientStrength, atmosphereDistance, view, light, divisor, adaptive, debug, colorSpace, temporal);
    }
    public SkyCloudRendering withScattering(float forward, float backward, float backwardMix, float multiple, float ambient) {
        return new SkyCloudRendering(extinction, erosion, detailFrequency, forward, backward, backwardMix, multiple, ambient,
                atmosphereDistance, viewSteps, lightSteps, resolutionDivisor, adaptive, debug, colorSpace, temporal);
    }
    public SkyCloudRendering withDensityDetail(float extinction, float erosion, float frequency) {
        return new SkyCloudRendering(extinction, erosion, frequency, forwardAnisotropy, backwardAnisotropy, backwardWeight,
                multipleScattering, ambientStrength, atmosphereDistance, viewSteps, lightSteps, resolutionDivisor, adaptive, debug, colorSpace, temporal);
    }
    public SkyCloudRendering withDebug(DebugView value) {
        return new SkyCloudRendering(extinction, erosion, detailFrequency, forwardAnisotropy, backwardAnisotropy, backwardWeight,
                multipleScattering, ambientStrength, atmosphereDistance, viewSteps, lightSteps, resolutionDivisor, adaptive, value, colorSpace, temporal);
    }
    public SkyCloudRendering withColorSpace(ColorSpace value) {
        return new SkyCloudRendering(extinction, erosion, detailFrequency, forwardAnisotropy, backwardAnisotropy, backwardWeight,
                multipleScattering, ambientStrength, atmosphereDistance, viewSteps, lightSteps, resolutionDivisor, adaptive, debug, value, temporal);
    }
    public SkyCloudRendering withTemporal(SkyCloudTemporal value) {
        return new SkyCloudRendering(extinction, erosion, detailFrequency, forwardAnisotropy, backwardAnisotropy, backwardWeight,
                multipleScattering, ambientStrength, atmosphereDistance, viewSteps, lightSteps, resolutionDivisor, adaptive, debug, colorSpace, value);
    }
    public enum DebugView { NONE, DENSITY, TRANSMITTANCE, RADIANCE, MARCH_STEPS, HISTORY_VALIDITY }
    /** DISPLAY_SRGB explicitly decodes the host before composition and encodes
     * the result once. LINEAR_HDR preserves linear radiance for HDR pipelines. */
    public enum ColorSpace { DISPLAY_SRGB, LINEAR_HDR }
}
