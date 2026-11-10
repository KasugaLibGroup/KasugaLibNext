package lib.kasuga.rendering.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShallowWaterGridTest {
    @Test void stillWaterRemainsStill() {
        var water = new ShallowWaterGrid(48, .25f, .32f);
        for (int i = 0; i < 200; i++) water.step(.05f);
        assertEquals(.32, water.height(0, 0), 1e-6);
        assertEquals(0, water.maximumDeviation(), 1e-6);
    }

    @Test void displacedWaterConservesVolumeAndPropagatesBeyondTheFoot() {
        var water = new ShallowWaterGrid(64, .2f, .32f);
        double volume = water.volume();
        water.displace(0, 0, .45f, .045f);
        assertTrue(water.height(0, 0) < .30f, "The foot must physically displace the surface");
        assertEquals(volume, water.volume(), volume * 1e-6);
        for (int i = 0; i < 30; i++) water.step(.05f);
        assertTrue(Math.abs(water.height(2, 0) - .32f) > 1e-5, "Waves must leave the foot's radius");
        assertEquals(volume, water.volume(), volume * 1e-4);
        assertTrue(water.isFinite());
    }

    @Test void movingFootTransfersMomentumAndLongStepsStayFinite() {
        var water = new ShallowWaterGrid(48, .25f, .32f);
        water.impulse(0, 0, .6f, 1.2f, -.3f);
        assertTrue(water.velocityX(0, 0) > 0);
        assertTrue(water.velocityZ(0, 0) < 0);
        for (int i = 0; i < 300; i++) {
            if (i % 13 == 0) water.displace(1, -1, .45f, .03f);
            water.step(i % 3 == 0 ? .2f : .016f);
        }
        assertTrue(water.isFinite());
        assertTrue(water.minimumDepth() > 0);
        assertTrue(water.maximumDeviation() < .32);
    }

    @Test void recenterKeepsTheExistingWaveInWorldSpace() {
        var water = new ShallowWaterGrid(48, .25f, .32f);
        water.displace(0, 0, .5f, .03f);
        float before = water.height(0, 0);
        water.shift(4, -2);
        assertEquals(before, water.height(-1, .5f), 1e-6);
        water.reset();
        assertEquals(0, water.maximumDeviation(), 1e-6);
    }

    @Test void invalidInputsAreRejectedBeforeContaminatingTheGrid() {
        assertThrows(IllegalArgumentException.class, () -> new ShallowWaterGrid(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShallowWaterGrid(32, Float.NaN, 1));
        var water = new ShallowWaterGrid(32, .25f, .32f);
        assertThrows(IllegalArgumentException.class, () -> water.step(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> water.displace(0, 0, 0, .1f));
        assertThrows(IllegalArgumentException.class, () -> water.impulse(0, 0, 1, Float.NaN, 0));
        assertTrue(water.isFinite());
    }
}
