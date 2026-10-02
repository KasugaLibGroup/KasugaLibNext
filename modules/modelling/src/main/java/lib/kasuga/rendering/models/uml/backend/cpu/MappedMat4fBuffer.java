package lib.kasuga.rendering.models.uml.backend.cpu;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class MappedMat4fBuffer extends MappedBuffer<Matrix4f> {

    public MappedMat4fBuffer(int dataSize) {
        super(dataSize, Matrix4f.class);
    }

    public MappedMat4fBuffer(Matrix4f[] data) {
        super(data, Matrix4f.class);
    }

    @Override
    public Matrix4f getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        return new Matrix4f(
                slice.getFloat(0), slice.getFloat(Float.BYTES), slice.getFloat(2 * Float.BYTES), slice.getFloat(3 * Float.BYTES),
                slice.getFloat(4 * Float.BYTES), slice.getFloat(5 * Float.BYTES), slice.getFloat(6 * Float.BYTES), slice.getFloat(7 * Float.BYTES),
                slice.getFloat(8 * Float.BYTES), slice.getFloat(9 * Float.BYTES), slice.getFloat(10 * Float.BYTES), slice.getFloat(11 * Float.BYTES),
                slice.getFloat(12 * Float.BYTES), slice.getFloat(13 * Float.BYTES), slice.getFloat(14 * Float.BYTES), slice.getFloat(15 * Float.BYTES)
        );
    }

    @Override
    public void writeData(Matrix4f value, int index) {
        checkIndex(index);
        long address = super.address + (long) index * sizeOfType();
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(address + i * Float.BYTES,
                    value.get(i % 4, i / 4));
        }
    }

    @Override
    public int sizeOfType() {
        return 16 * Float.BYTES;
    }
}
