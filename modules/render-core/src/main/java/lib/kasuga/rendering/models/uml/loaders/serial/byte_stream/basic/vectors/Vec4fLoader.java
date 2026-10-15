package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.vectors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

public class Vec4fLoader implements DataLoader<Vector4f> {
    @Override
    public int length(ByteBuffer buffer) {
        return 4 * Float.BYTES;
    }

    @Override
    public Vector4f process(ByteBuffer buffer, int length, SerialContext context) {
        return new Vector4f(
                buffer.getFloat(),
                buffer.getFloat(),
                buffer.getFloat(),
                buffer.getFloat()
        );
    }
}
