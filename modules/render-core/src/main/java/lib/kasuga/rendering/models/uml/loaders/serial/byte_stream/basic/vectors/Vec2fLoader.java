package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.vectors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;
import org.joml.Vector2f;

import java.nio.ByteBuffer;

public class Vec2fLoader implements DataLoader<Vector2f> {
    @Override
    public int length(ByteBuffer buffer) {
        return 2 * Float.BYTES;
    }

    @Override
    public Vector2f process(ByteBuffer buffer, int length, SerialContext context) {
        return new Vector2f(
                buffer.getFloat(),
                buffer.getFloat()
        );
    }
}
