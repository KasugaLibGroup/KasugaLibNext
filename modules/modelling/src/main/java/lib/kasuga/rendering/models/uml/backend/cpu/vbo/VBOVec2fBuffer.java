package lib.kasuga.rendering.models.uml.backend.cpu.vbo;

import lib.kasuga.rendering.models.uml.backend.cpu.MappedBuffer;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class VBOVec2fBuffer extends MappedBuffer<Vector2f> {
    public VBOVec2fBuffer(int dataSize) {
        super(dataSize, Vector2f.class);
    }

    public VBOVec2fBuffer(Vector2f[] data) {
        super(data, Vector2f.class);
    }

    @Override
    public Vector2f getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        return new Vector2f(
            slice.getFloat(0),
            slice.getFloat(Float.BYTES)
        );
    }

    @Override
    public void writeData(Vector2f value, int index) {
        checkIndex(index);
        long address = super.address + (long) index * sizeOfType();
        MemoryUtil.memPutFloat(address, value.x);
        MemoryUtil.memPutFloat(address + Float.BYTES, value.y);
    }

    @Override
    public int sizeOfType() {
        return 2 * Float.BYTES;
    }
}
