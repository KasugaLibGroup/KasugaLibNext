package lib.kasuga.rendering.lighting;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class LightSourceTest {
    @Test void temperatureIsOptionalAndProducesWarmAndCoolLinearTints() {
        assertEquals(LightColor.WHITE, LightSource.point(0, 0, 0).build().color());
        LightColor warm = LightColor.temperature(2700);
        LightColor cool = LightColor.temperature(12000);
        assertTrue(warm.red() > warm.green() && warm.green() > warm.blue());
        assertTrue(cool.blue() > cool.green() && cool.green() > cool.red());
        assertEquals(warm, LightSource.point(0, 0, 0).temperature(2700).build().color());
    }

    @Test void settingsCopiesKeepGeometryAndMediumWhileMoving() {
        LightSource original = LightSource.area(1, 2, 3, 4, 6).rotation(30, 90)
                .temperature(3200).intensity(8).range(20).twoSided(true).medium(.2f, .7f).build();
        LightSource moved = original.toBuilder().position(10, 20, 30).intensity(0).build();
        assertEquals(original, moved.toBuilder().position(1, 2, 3).intensity(8).build());
        assertEquals(20 + Math.hypot(4, 6) / 2, original.boundsRadius(), 1e-6);
        assertEquals(16, LightSource.volumetric(0, 0, 0).build().boundsRadius());
    }

    @Test void invalidInputsFailBeforeReachingTheGpu() {
        assertThrows(IllegalArgumentException.class, () -> LightColor.temperature(999));
        assertThrows(IllegalArgumentException.class, () -> LightColor.temperature(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new LightColor(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LightSource.point(Double.NaN, 0, 0).build());
        assertThrows(IllegalArgumentException.class, () -> LightSource.point(0, 0, 0).range(0).build());
        assertThrows(IllegalArgumentException.class, () -> LightSource.point(0, 0, 0).intensity(-1).build());
        assertThrows(IllegalArgumentException.class, () -> LightSource.area(0, 0, 0, 0, 1).build());
        assertThrows(IllegalArgumentException.class, () -> LightSource.volumetric(0, 0, 0).medium(.1f, 1).build());
        assertThrows(IllegalArgumentException.class, () -> LightSource.volumetric(0, 0, 0).medium(Float.POSITIVE_INFINITY, 0).build());
    }

    @Test void packingRebasesBeforeFloatConversionAndPreservesRectangleOrientation() {
        ByteBuffer data = ByteBuffer.allocate(LightBuffer.BYTES_PER_LIGHT).order(ByteOrder.nativeOrder());
        LightSource light = LightSource.area(29_999_999.125, 60.5, -29_999_999.25, 4, 6)
                .rotation(0, 90).build();
        LightBuffer.write(data, light, 29_999_999, 60, -29_999_999);
        assertEquals(LightBuffer.BYTES_PER_LIGHT, data.position());
        assertEquals(.125f, data.getFloat(0));
        assertEquals(.5f, data.getFloat(4));
        assertEquals(-.25f, data.getFloat(8));
        // U x V = (0,-1,0): a ceiling panel at pitch +90 emits downward.
        assertEquals(1, data.getFloat(32), 1e-6);
        assertEquals(1, data.getFloat(56), 1e-6);
        assertEquals(2, data.getFloat(44));
        assertEquals(3, data.getFloat(60));
    }
}
