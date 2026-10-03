package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lombok.Getter;
import lombok.Setter;

import javax.lang.model.type.NullType;
import java.nio.ByteBuffer;

public class Padding implements DataLoader<NullType> {

    @Getter
    private int bytes;

    public Padding(int bytes) {
        this.bytes = Math.clamp(bytes, 1, Integer.MAX_VALUE);
    }

    public Padding() {
        this(1);
    }

    public void setBytes(int bytes) {
        this.bytes = Math.clamp(bytes, 1, Integer.MAX_VALUE);
    }

    @Override
    public int length(ByteBuffer buffer) {
        return bytes;
    }

    @Override
    public NullType process(ByteBuffer buffer, int length, SerialContext context) {
        return null;
    }
}
