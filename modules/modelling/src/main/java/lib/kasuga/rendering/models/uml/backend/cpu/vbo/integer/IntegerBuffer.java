package lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer;

import lib.kasuga.rendering.models.uml.backend.cpu.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public abstract class IntegerBuffer<T> extends MappedBuffer<T> {

    public IntegerBuffer(int dataSize, Class<T> type) {
        super(dataSize, type);
    }

    public IntegerBuffer(T[] data, Class<T> type) {
        super(data, type);
    }

    public abstract int convertToInt(T value);

    public abstract T convertFromInt(int value);

    @Override
    public T getData(ByteBuffer slice) {
        checkOpen("Buffer is closed");
        return convertFromInt(slice.getInt(0));
    }

    @Override
    public void writeData(T value, int index) {
        checkIndex(index);
        MemoryUtil.memPutInt(super.address + (long) index * sizeOfType(), convertToInt(value));
    }

    @Override
    public int sizeOfType() {
        return Integer.BYTES;
    }
}
