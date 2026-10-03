package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.numbers;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;

import java.nio.ByteBuffer;

public class UIntLoader implements DataLoader<Long> {
    @Override
    public int length(ByteBuffer buffer) {
        return Integer.BYTES;
    }

    @Override
    public Long process(ByteBuffer buffer, int length, SerialContext context) {
        return Integer.toUnsignedLong(buffer.getInt());
    }
}
