package lib.kasuga.rendering.cloud;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SkyCloudFieldTest {
    @Test void layersAreIndependentAndSettingsOwnAnImmutableCopy() {
        var layers = new java.util.ArrayList<>(SkyCloudSettings.DEFAULT.layers());
        var settings = SkyCloudSettings.DEFAULT.withLayers(layers);
        layers.clear();
        assertEquals(3, settings.layers().size());
        assertEquals(SkyCloudLayer.Type.CUMULUS, settings.layers().get(0).type());
        assertEquals(900, settings.layers().get(0).baseHeight());
        assertEquals(2700, settings.layers().get(1).baseHeight());
        assertEquals(5400, settings.layers().get(2).baseHeight());
        assertEquals(settings.layers(), settings.withCoverage(.4f).withQuality(SkyCloudSettings.Quality.FAST).layers());
        assertThrows(UnsupportedOperationException.class, () -> settings.layers().clear());
        var layer = settings.layers().get(0).atHeight(1200).withDensity(.4f);
        assertEquals(1200, layer.baseHeight()); assertEquals(.4f, layer.density());
        assertEquals(settings.layers().get(0).thickness(), layer.thickness());
        assertThrows(IllegalArgumentException.class, () -> layer.withDensity(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> settings.withLayers(java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> settings.withLayers(java.util.Collections.nCopies(5, layer)));
    }
    @Test void eachLayerWeatherRetainsOverlapAndItsOwnDistribution() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT);
        var a = field.sample(-.01, 64, -.01, 1, 0);
        var b = field.sample(.01, 64, .01, 1, 0);
        for (int layer = 0; layer < 4; layer++) for (int z = 0; z < 31; z++) for (int x = 0; x < 31; x++) for (int c = 0; c < 4; c++)
            assertEquals(a.window().weather(layer, x + 1, z + 1, c), b.window().weather(layer, x, z, c));
        assertNotEquals(a.window().weather(0, 16, 16, 0), a.window().weather(1, 16, 16, 0));
        float[] weather = a.window().weatherLayers(); weather[0] = 42;
        assertNotEquals(42, a.window().weather(0, 0, 0, 0));
    }
    @Test void weatherAndStormPlacementAreDeterministicAcrossObservers() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT);
        var a = field.sample(0, 64, 0, 1, 0);
        var b = new SkyCloudField(SkyCloudSettings.DEFAULT).sample(0, 64, 0, 1, 0);
        assertArrayEquals(a.window().layout(), b.window().layout());
        assertTrue(a.weatherCellCount() > 200 && a.weatherCellCount() < 900);
        int[] types = new int[3];
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) types[(int) a.window().value(x, z, 2)]++;
        assertTrue(types[0] > 500); assertTrue(types[1] > 0 && types[1] < 40); assertTrue(types[2] > 50);
        assertFalse(Arrays.equals(a.window().layout(), new SkyCloudField(SkyCloudSettings.DEFAULT.withSeed(321)).sample(0, 64, 0, 1, 0).window().layout()));
    }
    @Test void crossingACellRetainsEveryOverlappingWorldCloud() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT);
        var a = field.sample(-.01, 64, -.01, 1, 0);
        var b = field.sample(.01, 64, .01, 1, 0);
        assertEquals(a.window().key().x() + 1, b.window().key().x());
        for (int z = 0; z < 31; z++) for (int x = 0; x < 31; x++) for (int c = 0; c < 4; c++)
            assertEquals(a.window().value(x + 1, z + 1, c), b.window().value(x, z, c));
    }
    @Test void windIsTickOwnedAndCoordinatesRetainWorldBorderPrecision() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT);
        var before = field.sample(29_999_999.125, 64, -29_999_999.25, 1, 0);
        field.tick(.05);
        var half = field.sample(29_999_999.125, 64, -29_999_999.25, .5f, 0);
        assertEquals(.025, half.timeSeconds());
        assertEquals(.2, half.originX() - before.originX(), .002);
        assertEquals(.075, half.originZ() - before.originZ(), .002);
        for (int i = 0; i < 20; i++) assertEquals(half, field.sample(29_999_999.125, 64, -29_999_999.25, .5f, 0));
        assertEquals(836, half.baseY());
    }
    @Test void allYawRotatedCloudBoxesFitInsideTheirWeatherCell() {
        var frame = new SkyCloudField(SkyCloudSettings.DEFAULT).sample(0, 64, 0, 1, 0);
        var layout = frame.window().layout(); var dimensions = frame.window().dimensions();
        for (int i = 0; i < layout.length; i += 4) {
            float c = Math.abs((float) Math.cos(dimensions[i + 3])), s = Math.abs((float) Math.sin(dimensions[i + 3]));
            assertTrue(Math.abs(layout[i]) + (c * dimensions[i] + s * dimensions[i + 2]) * .5 < .5);
            assertTrue(Math.abs(layout[i + 1]) + (s * dimensions[i] + c * dimensions[i + 2]) * .5 < .5);
        }
    }
    @Test void emptyFullAndRainCoverageAndCachesAreBounded() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT.withCoverage(0));
        assertEquals(0, field.sample(0, 64, 0, 1, 0).weatherCellCount());
        field.settings(SkyCloudSettings.DEFAULT.withCoverage(1));
        assertEquals(1024, field.sample(0, 64, 0, 1, 0).weatherCellCount());
        field.settings(SkyCloudSettings.DEFAULT);
        assertTrue(field.sample(0, 64, 0, 1, 1).weatherCellCount() > field.sample(0, 64, 0, 1, 0).weatherCellCount());
        for (int i = 0; i < 40; i++) field.sample(i * 5000, 64, 0, 1, 0);
        assertEquals(8, field.cachedWindowCount());
        var layout = field.sample(0, 64, 0, 1, 0).window().layout(); layout[0] = 42;
        assertNotEquals(42, field.sample(0, 64, 0, 1, 0).window().value(0, 0, 0));
    }
    @Test void cloudBanksHaveCorrelatedCoverageAndBroadSizeVariation() {
        var frame = new SkyCloudField(SkyCloudSettings.DEFAULT).sample(0, 64, 0, 1, 0);
        var dimensions = frame.window().dimensions();
        float smallest = 1, largest = 0; double adjacent = 0, distant = 0;
        for (int z = 0; z < 24; z++) for (int x = 0; x < 24; x++) {
            float probability = frame.window().value(x, z, 3);
            adjacent += Math.abs(probability - frame.window().value(x + 1, z, 3));
            distant += Math.abs(probability - frame.window().value(x + 8, z + 8, 3));
            float size = dimensions[(z * 32 + x) * 4]; smallest = Math.min(smallest, size); largest = Math.max(largest, size);
        }
        assertTrue(adjacent < distant * .8, "Cloud cover must form banks rather than independent cells");
        assertTrue(largest / smallest > 3, "Cloud sizes must vary beyond small random adjustments");
    }
    @Test void changingWindDoesNotTeleportExistingCloudBanks() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT); field.tick(10);
        var before = field.sample(0, 64, 0, 1, 0);
        var s = field.settings();
        field.settings(new SkyCloudSettings(s.seed(), s.cellSize(), s.baseHeight(), s.maxHeight(), s.coverage(), s.distance(), -8, -3, s.quality()));
        var after = field.sample(0, 64, 0, 1, 0);
        assertEquals(before.originX(), after.originX()); assertEquals(before.originZ(), after.originZ());
        field.tick(1);
        assertEquals(before.originX() - 8, field.sample(0, 64, 0, 1, 0).originX());
    }
    @Test void invalidInputsDoNotAdvanceTheField() {
        var field = new SkyCloudField(SkyCloudSettings.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> field.tick(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> field.tick(-1));
        assertThrows(IllegalArgumentException.class, () -> field.sample(Double.POSITIVE_INFINITY, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> SkyCloudSettings.DEFAULT.withCoverage(Float.NaN));
        assertEquals(0, field.sample(0, 0, 0, 1, 0).timeSeconds());
    }
    @Test void sharedShapeAtlasPreservesStormAndAddsOrdinaryClouds() {
        var storm = CumulonimbusShape.bake(12); var shapes = CloudShapes.bake(12);
        float cumulus = 0, flat = 0;
        for (int i = 0; i < storm.length / 2; i++) {
            assertEquals(storm[i * 2], shapes[i * 4]); assertEquals(storm[i * 2 + 1], shapes[i * 4 + 1]);
            cumulus += shapes[i * 4 + 2]; flat += shapes[i * 4 + 3];
        }
        assertTrue(cumulus > 50 && flat > cumulus);
    }
}
