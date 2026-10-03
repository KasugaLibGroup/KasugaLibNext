package lib.kasuga.rendering.models.uml.backend.cpu;

import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class MappedV4fBuffer extends MappedBuffer<Vector4f> {

    public MappedV4fBuffer(Vector4f[] data) {
        super(data, Vector4f.class);
    }

    public MappedV4fBuffer(int dataSize) {
        super(dataSize, Vector4f.class);
    }

    @Override
    public Vector4f getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        return new Vector4f(
            slice.getFloat(0),
            slice.getFloat(Float.BYTES),
            slice.getFloat(2 * Float.BYTES),
            slice.getFloat(3 * Float.BYTES)
        );
    }

    @Override
    public void writeData(Vector4f value, int index) {
        checkIndex(index);
        long address = super.address + (long) index * sizeOfType();
        MemoryUtil.memPutFloat(address, value.x);
        MemoryUtil.memPutFloat(address + Float.BYTES, value.y);
        MemoryUtil.memPutFloat(address + 2 * Float.BYTES, value.z);
        MemoryUtil.memPutFloat(address + 3 * Float.BYTES, value.w);
    }

    @Override
    public int sizeOfType() {
        return 4 * Float.BYTES;
    }
}
