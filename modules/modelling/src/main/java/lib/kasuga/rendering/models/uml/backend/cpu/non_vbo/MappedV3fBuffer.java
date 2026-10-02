package lib.kasuga.rendering.models.uml.backend.cpu.non_vbo;

import lib.kasuga.rendering.models.uml.backend.cpu.MappedBuffer;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class MappedV3fBuffer extends MappedBuffer<Vector3f> {

    public MappedV3fBuffer(int dataSize) {
        super(dataSize, Vector3f.class);
    }

    public MappedV3fBuffer(Vector3f[] data) {
        super(data, Vector3f.class);
    }

    @Override
    public Vector3f getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        return new Vector3f(
            slice.getFloat(0),
            slice.getFloat(Float.BYTES),
            slice.getFloat(2 * Float.BYTES)
        );
    }

    @Override
    public void writeData(Vector3f value, int index) {
        checkIndex(index);
        long address = super.address + (long) index * sizeOfType();
        MemoryUtil.memPutFloat(address, value.x);
        MemoryUtil.memPutFloat(address + Float.BYTES, value.y);
        MemoryUtil.memPutFloat(address + 2 * Float.BYTES, value.z);
    }

    @Override
    public int sizeOfType() {
        return 4 * Float.BYTES;
    }
}
