package lib.kasuga.rendering.weather;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

final class WeatherFilterVolumeTest {

    @Test
    void rejectsInvalidDimensionsStrengthFeatherAndSingularTransforms() {
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(0, 2, 2).build());
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(2, -1, 2).build());
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(Double.NaN, 2, 2).build());
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(2, 2, 2).strength(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(2, 2, 2).strength(1.1f).build());
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(2, 2, 2).feather(0.5f).build());
        assertThrows(IllegalArgumentException.class, () -> WeatherFilterVolume.box(2, 2, 2).rotation(Float.NaN, 0, 0).build());

        WeatherFilterVolume valid = WeatherFilterVolume.box(4, 3, 10).build();
        Matrix4d singular = new Matrix4d().scale(1, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> new WeatherFilterFrame(valid, singular));
    }

    @Test
    void rotatedAndFeatheredObbComputesOcclusionAndVerticalClip() {
        WeatherFilterVolume volume = WeatherFilterVolume.box(4.0, 2.0, 8.0)
                .position(10.0, 70.0, 0.0)
                .rotation(45.0f, 0.0f, 0.0f)
                .strength(1.0f)
                .feather(0.1f)
                .build();
        WeatherFilterFrame frame = WeatherFilterFrame.world(volume);

        assertEquals(1.0f, frame.occlusionAt(10.0, 70.0, 0.0), 1e-5f);
        assertEquals(0.0f, frame.occlusionAt(15.0, 70.0, 0.0), 1e-5f);

        // Point rotated along the 45-degree Z extent (distance 2.5 along local Z < 4.0 half-length)
        double diag = 2.5 / Math.sqrt(2.0);
        assertEquals(1.0f, frame.occlusionAt(10.0 + diag, 70.0, diag), 1e-5f);

        // Feather ramp near top boundary: local y = 0.45 -> (0.5 - 0.45) / 0.1 = 0.5
        assertEquals(0.5f, frame.occlusionAt(10.0, 70.9, 0.0), 1e-4f);

        OptionalDouble hit = frame.clipVertical(10.0, 60.0, 80.0, 0.0);
        assertTrue(hit.isPresent());
        assertEquals(71.0, hit.getAsDouble(), 1e-5);

        OptionalDouble miss = frame.clipVertical(15.0, 60.0, 80.0, 0.0);
        assertTrue(miss.isEmpty());

        // Partial strength volume does not hard-clip vertical columns in CPU fallback
        WeatherFilterFrame partial = WeatherFilterFrame.world(volume.toBuilder().strength(0.5f).build());
        assertTrue(partial.clipVertical(10.0, 60.0, 80.0, 0.0).isEmpty());
        assertEquals(0.5f, partial.occlusionAt(10.0, 70.0, 0.0), 1e-5f);
    }

    @Test
    void bufferPackingPreservesPrecisionAtThirtyMillionWorldCoordinates() {
        double eyeX = 29_999_984.25;
        double eyeY = 80.5;
        double eyeZ = -29_999_950.75;

        WeatherFilterVolume volume = WeatherFilterVolume.box(3.5, 2.5, 12.0)
                .position(2.0, 0.0, -1.5)
                .rotation(33.0f, 12.0f, -6.0f)
                .strength(0.75f)
                .feather(0.05f)
                .build();
        Matrix4d parent = new Matrix4d()
                .translate(eyeX + 1.25, eyeY - 0.5, eyeZ + 2.0)
                .rotateY(0.4);
        WeatherFilterFrame frame = new WeatherFilterFrame(volume, parent);

        ByteBuffer buffer = ByteBuffer.allocateDirect(WeatherFilterBuffer.BYTES_PER_VOLUME)
                .order(ByteOrder.nativeOrder());
        WeatherFilterBuffer.write(buffer, frame, eyeX, eyeY, eyeZ);
        buffer.flip();

        float[] data = new float[16];
        for (int i = 0; i < 16; i++) {
            data[i] = buffer.getFloat();
            assertTrue(Float.isFinite(data[i]), "Non-finite buffer element at " + i);
        }
        assertEquals(0.75f, data[12], 1e-6f);
        assertEquals(0.05f, data[13], 1e-6f);

        // Reconstruct local coordinates from camera-relative offset as the fragment shader does
        Vector3d rel = new Vector3d(3.1, -0.4, 0.7);
        double lx = data[0] * rel.x + data[1] * rel.y + data[2] * rel.z + data[3];
        double ly = data[4] * rel.x + data[5] * rel.y + data[6] * rel.z + data[7];
        double lz = data[8] * rel.x + data[9] * rel.y + data[10] * rel.z + data[11];

        Vector3d expected = frame.worldToUnitBox()
                .transformPosition(eyeX + rel.x, eyeY + rel.y, eyeZ + rel.z, new Vector3d());
        assertEquals(expected.x, lx, 1e-5);
        assertEquals(expected.y, ly, 1e-5);
        assertEquals(expected.z, lz, 1e-5);
    }
}
