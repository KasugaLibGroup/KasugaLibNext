package lib.kasuga.rendering.models.mc.backend.data_type.buffer;

import lib.kasuga.rendering.models.uml.backend.cpu.non_vbo.MappedV3fBuffer;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class PackedNormalBuffer extends MappedV3fBuffer {

    public PackedNormalBuffer(int dataSize) {
        super(dataSize);
    }

    public PackedNormalBuffer(Vector3f[] data) {
        super(data);
    }

    @Override
    public Vector3f getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        return new Vector3f(
            byteToNormal(slice.get(0)),
            byteToNormal(slice.get(1)),
            byteToNormal(slice.get(2))
        );
    }

    @Override
    public void writeData(Vector3f value, int index) {
        checkIndex(index);
        long address = super.address + (long) index * sizeOfType();
        MemoryUtil.memPutFloat(address, normalToByte(value.x));
        MemoryUtil.memPutFloat(address + Byte.BYTES, normalToByte(value.y));
        MemoryUtil.memPutFloat(address + 2 * Byte.BYTES, normalToByte(value.z));
    }

    public static byte normalToByte(float value) {
        return (byte) ((int) (Math.clamp(value, -1.0f, 1.0f) * 127.0f) & 0xFF);
    }

    public static float byteToNormal(byte value) {
        return (value & 0xFF) / 127.0f - 1.0f;
    }

    @Override
    public int sizeOfType() {
        return 3 * Byte.BYTES;
    }
}
