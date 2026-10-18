package lib.kasuga.rendering.models.uml.backend.cpu.vbo;

import lib.kasuga.rendering.models.uml.backend.cpu.MappedBuffer;
import org.joml.Matrix3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class VBOMat3fBuffer extends MappedBuffer<Matrix3f> {

    public VBOMat3fBuffer(int dataSize) {
        super(dataSize, Matrix3f.class);
    }

    public VBOMat3fBuffer(Matrix3f[] data) {
        super(data, Matrix3f.class);
    }

    @Override
    public Matrix3f getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        float m00 = slice.getFloat(0);
        float m01 = slice.getFloat(Float.BYTES);
        float m02 = slice.getFloat(2 * Float.BYTES);

        float m10 = slice.getFloat(3 * Float.BYTES);
        float m11 = slice.getFloat(4 * Float.BYTES);
        float m12 = slice.getFloat(5 * Float.BYTES);

        float m20 = slice.getFloat(6 * Float.BYTES);
        float m21 = slice.getFloat(7 * Float.BYTES);
        float m22 = slice.getFloat(8 * Float.BYTES);
        return new Matrix3f(
            m00, m01, m02,
            m10, m11, m12,
            m20, m21, m22
        );
    }

    @Override
    public void writeData(Matrix3f value, int index) {
        checkIndex(index);
        long address = super.address + (long) index * sizeOfType();
        MemoryUtil.memPutFloat(address, value.m00());
        MemoryUtil.memPutFloat(address + Float.BYTES, value.m01());
        MemoryUtil.memPutFloat(address + 2 * Float.BYTES, value.m02());

        MemoryUtil.memPutFloat(address + 3 * Float.BYTES, value.m10());
        MemoryUtil.memPutFloat(address + 4 * Float.BYTES, value.m11());
        MemoryUtil.memPutFloat(address + 5 * Float.BYTES, value.m12());

        MemoryUtil.memPutFloat(address + 6 * Float.BYTES, value.m20());
        MemoryUtil.memPutFloat(address + 7 * Float.BYTES, value.m21());
        MemoryUtil.memPutFloat(address + 8 * Float.BYTES, value.m22());
    }

    @Override
    public int sizeOfType() {
        return 9 * Float.BYTES;
    }
}
