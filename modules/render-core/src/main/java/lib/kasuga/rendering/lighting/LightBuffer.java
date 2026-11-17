package lib.kasuga.rendering.lighting;

import java.nio.ByteBuffer;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Shared five-vec4 GPU layout. Subtract the double-precision camera before converting positions. */
public final class LightBuffer {
    public static final int BYTES_PER_LIGHT = 5 * 4 * Float.BYTES;

    private LightBuffer() {}

    public static void write(ByteBuffer output, LightSource light, double eyeX, double eyeY, double eyeZ) {
        write(output, LightFrame.world(light), eyeX, eyeY, eyeZ);
    }

    public static void write(ByteBuffer output, LightFrame frame, double eyeX, double eyeY, double eyeZ) {
        LightSource light = frame.source();
        Vector3d position = frame.position();
        output.putFloat((float) (position.x - eyeX)).putFloat((float) (position.y - eyeY))
                .putFloat((float) (position.z - eyeZ)).putFloat((float) (light.range() * frame.rangeScale()));
        output.putFloat(light.color().red()).putFloat(light.color().green())
                .putFloat(light.color().blue()).putFloat(light.intensity());
        double yaw = Math.toRadians(light.yaw());
        double pitch = Math.toRadians(light.pitch());
        Matrix4d transform = frame.transform();
        Vector3d axisU = transform.transformDirection(new Vector3d(Math.cos(yaw), 0, -Math.sin(yaw)));
        Vector3d axisV = transform.transformDirection(new Vector3d(Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch), Math.cos(yaw) * Math.sin(pitch)));
        // Axis lengths encode the transformed physical dimensions. Cross product gives area and normal in GLSL.
        output.putFloat((float) axisU.x).putFloat((float) axisU.y).putFloat((float) axisU.z).putFloat(light.width() * .5f);
        output.putFloat((float) axisV.x).putFloat((float) axisV.y).putFloat((float) axisV.z).putFloat(light.height() * .5f);
        output.putFloat(light.type().ordinal()).putFloat(light.twoSided() ? 1 : 0)
                .putFloat(light.density()).putFloat(light.anisotropy());
    }
}
