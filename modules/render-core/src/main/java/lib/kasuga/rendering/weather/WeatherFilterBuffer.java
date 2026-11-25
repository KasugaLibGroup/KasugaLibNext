package lib.kasuga.rendering.weather;

import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.nio.ByteBuffer;

/**
 * Packs {@link WeatherFilterFrame} into four {@code vec4} texels (64 bytes) for a
 * {@code GL_RGBA32F} 2D texture, subtracting the double-precision camera origin before
 * narrowing to 32-bit floats.
 */
public final class WeatherFilterBuffer {
    public static final int TEXELS_PER_VOLUME = 4;
    public static final int BYTES_PER_VOLUME = TEXELS_PER_VOLUME * 4 * Float.BYTES;

    private WeatherFilterBuffer() {}

    public static void write(ByteBuffer output, WeatherFilterFrame frame, double eyeX, double eyeY, double eyeZ) {
        Matrix4d inv = frame.worldToUnitBox();
        Vector3d originInUnit = inv.transformPosition(eyeX, eyeY, eyeZ, new Vector3d());
        WeatherFilterVolume volume = frame.volume();

        // Row 0 of worldToUnitBox with camera-relative offset
        output.putFloat((float) inv.m00()).putFloat((float) inv.m10()).putFloat((float) inv.m20())
                .putFloat((float) originInUnit.x);
        // Row 1
        output.putFloat((float) inv.m01()).putFloat((float) inv.m11()).putFloat((float) inv.m21())
                .putFloat((float) originInUnit.y);
        // Row 2
        output.putFloat((float) inv.m02()).putFloat((float) inv.m12()).putFloat((float) inv.m22())
                .putFloat((float) originInUnit.z);
        // Parameters: strength, feather, reserved, reserved
        output.putFloat(volume.strength()).putFloat(volume.feather()).putFloat(0.0f).putFloat(0.0f);
    }
}
