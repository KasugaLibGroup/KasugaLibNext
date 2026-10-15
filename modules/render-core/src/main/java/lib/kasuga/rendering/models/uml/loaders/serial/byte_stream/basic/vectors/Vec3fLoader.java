package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.vectors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;
import org.joml.Vector3f;

import java.nio.ByteBuffer;

public class Vec3fLoader implements DataLoader<Vector3f> {
    @Override
    public int length(ByteBuffer buffer) {
        return 3 * Float.BYTES;
    }

    @Override
    public Vector3f process(ByteBuffer buffer, int length, SerialContext context) {
        return new Vector3f(
                buffer.getFloat(),
                buffer.getFloat(),
                buffer.getFloat()
        );
    }
}
