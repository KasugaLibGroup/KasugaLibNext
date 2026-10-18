package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;

import java.nio.ByteBuffer;

public interface DataLoader<OutputType> extends StreamLoader {

    int length(ByteBuffer buffer);

    OutputType process(ByteBuffer buffer, int length, SerialContext context);

    default OutputType load(ByteBuffer buffer, SerialContext context) {
        int offset = buffer.position();
        int length = length(buffer);
        if (length <= 0) {
            throw new IllegalStateException("Length must be POSITIVE!, got: " + length);
        }
        if (offset + length > buffer.limit()) {
            throw new IllegalStateException("Buffer has reach its end!, expected: " + length + "bytes, got: " + (buffer.limit() - offset) + "bytes");
        }
        OutputType output = process(buffer, length, context);
        if (buffer.position() == offset) buffer.position(offset + length);
        return output;
    }
}
