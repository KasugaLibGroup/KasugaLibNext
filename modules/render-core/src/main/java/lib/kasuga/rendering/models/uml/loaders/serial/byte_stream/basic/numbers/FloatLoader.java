package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.numbers;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;

import java.nio.ByteBuffer;

public class FloatLoader implements DataLoader<Float> {
    @Override
    public int length(ByteBuffer buffer) {
        return Float.BYTES;
    }

    @Override
    public Float process(ByteBuffer buffer, int length, SerialContext context) {
        return buffer.getFloat();
    }
}
