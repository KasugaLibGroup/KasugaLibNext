package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.numbers;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;

import java.nio.ByteBuffer;

public class ByteLoader implements DataLoader<Byte> {
    @Override
    public int length(ByteBuffer buffer) {
        return Byte.BYTES;
    }

    @Override
    public Byte process(ByteBuffer buffer, int length, SerialContext context) {
        return buffer.get();
    }
}
