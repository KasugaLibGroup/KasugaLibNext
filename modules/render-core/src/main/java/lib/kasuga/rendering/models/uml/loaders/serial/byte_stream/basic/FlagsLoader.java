package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;

import java.nio.ByteBuffer;

public class FlagsLoader implements DataLoader<boolean[]> {
    @Override
    public int length(ByteBuffer buffer) {
        return Byte.BYTES;
    }

    @Override
    public boolean[] process(ByteBuffer buffer, int length, SerialContext context) {
        boolean[] result = new boolean[Byte.SIZE];
        byte flags = buffer.get();
        for (int i = 0; i < Byte.SIZE; i++) {
            result[i] = (flags & (1 << i)) != 0;
        }
        return result;
    }
}
