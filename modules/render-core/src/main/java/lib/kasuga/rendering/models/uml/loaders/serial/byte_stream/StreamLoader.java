package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;

import java.nio.ByteBuffer;

public interface StreamLoader {

    Object load(ByteBuffer buffer, SerialContext context);
}
