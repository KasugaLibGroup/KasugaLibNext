package lib.kasuga.rendering.models.mc.backend.data_type.buffer;

import lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer.IntVec2iBuffer;
import org.joml.Vector2i;

public class PackedUVBuffer extends IntVec2iBuffer {

    public PackedUVBuffer(int dataSize) {
        super(dataSize);
    }

    public PackedUVBuffer(Vector2i[] data) {
        super(data);
    }

    @Override
    public int convertToInt(Vector2i value) {
        return isLittleEndian() ?
                value.x | value.y << 16
            : value.y | value.x << 16;
    }

    @Override
    public Vector2i convertFromInt(int value) {
        return isLittleEndian() ?
                new Vector2i(value & 0xFFFF, (value >> 16) & 0xFFFF) :
                new Vector2i((value >> 16) & 0xFFFF, value & 0xFFFF);
    }
}
