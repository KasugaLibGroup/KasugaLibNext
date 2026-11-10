package lib.kasuga.rendering.cloud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyCloudRenderingTest {
    @Test void overridesRemainAttachedWhenChangingWeatherOrLayers() {
        var optics = SkyCloudRendering.DEFAULT.withSampling(192, 5, 2, true)
                .withDensityDetail(.008f, .2f, 3.7f)
                .withScattering(.7f, -.3f, .2f, .4f, 1.2f);
        var settings = SkyCloudSettings.DEFAULT.withRendering(optics).withCoverage(.7f)
                .withSeed(42).withQuality(SkyCloudSettings.Quality.FAST).withWind(-2, 5)
                .withLayers(SkyCloudSettings.DEFAULT.layers());
        assertSame(optics, settings.rendering());
        assertEquals(192, optics.viewSteps(settings.quality()));
        assertEquals(5, optics.lightSteps(settings.quality()));
        assertEquals(2, optics.resolutionDivisor(settings.quality()));
        assertEquals(3.7f, optics.detailFrequency());
        assertEquals(112, SkyCloudRendering.DEFAULT.viewSteps(SkyCloudSettings.Quality.FAST));
        assertEquals(6, SkyCloudRendering.DEFAULT.lightSteps(SkyCloudSettings.Quality.HIGH));
    }
    @Test void invalidOpticsCannotReachTheShader() {
        var d = SkyCloudRendering.DEFAULT;
        assertThrows(IllegalArgumentException.class, () -> d.withSampling(1, 4, 2, true));
        assertThrows(IllegalArgumentException.class, () -> d.withSampling(160, 9, 2, true));
        assertThrows(IllegalArgumentException.class, () -> d.withSampling(160, 4, 5, true));
        assertThrows(IllegalArgumentException.class, () -> d.withDensityDetail(Float.NaN, .1f, 4));
        assertThrows(IllegalArgumentException.class, () -> d.withDensityDetail(.01f, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> d.withScattering(1, -.2f, .2f, .5f, 1));
        assertThrows(IllegalArgumentException.class, () -> d.withScattering(.6f, -.2f, .2f, .9f, 1));
        assertThrows(IllegalArgumentException.class, () -> new SkyCloudTemporal(Float.NaN, .2f, 16, .05f));
        assertThrows(IllegalArgumentException.class, () -> new SkyCloudTemporal(1, .2f, 16, .05f));
        assertThrows(IllegalArgumentException.class, () -> new SkyCloudTemporal(.7f, 0, 16, .05f));
        assertSame(SkyCloudTemporal.OFF, d.withTemporal(SkyCloudTemporal.OFF).withDebug(SkyCloudRendering.DebugView.DENSITY)
                .withSampling(128, 3, 2, false).withColorSpace(SkyCloudRendering.ColorSpace.LINEAR_HDR).temporal());
    }
}
