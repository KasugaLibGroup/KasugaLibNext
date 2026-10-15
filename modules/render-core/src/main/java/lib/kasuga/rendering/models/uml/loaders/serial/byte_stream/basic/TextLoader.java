package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lombok.Getter;
import lombok.Setter;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class TextLoader implements DataLoader<String> {

    @Getter
    @Setter
    private Charset encoding;

    public TextLoader(Charset encoding) {
        this.encoding = encoding;
    }

    @Override
    public int length(ByteBuffer buffer) {
        return buffer.getInt() + Integer.BYTES;
    }

    @Override
    public String process(ByteBuffer buffer, int length, SerialContext context) {
        byte[] bytes = new byte[length - Integer.BYTES];
        buffer.get(bytes);
        return new String(bytes, encoding);
    }
}
