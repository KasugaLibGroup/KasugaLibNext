package lib.kasuga.rendering.models.uml.backend.cpu;

import lib.kasuga.rendering.models.uml.framework.buffer.TypedBuffer;
import lombok.Getter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public abstract class MappedBuffer<T> implements TypedBuffer<T> {

    protected static final MemoryUtil.MemoryAllocator ALLOCATOR = MemoryUtil.getAllocator(false);

    @Getter
    protected ByteBuffer buffer;

    @Getter
    protected Object[] data;

    @Getter
    protected Class<T> type;

    @Getter
    protected boolean isClosed = false;

    @Getter
    protected final ByteOrder order;

    @Getter
    protected final long address;

    private final int capacity;

    public MappedBuffer(int dataSize, Class<T> type) {
        if (dataSize < 0) throw new IllegalArgumentException("Negative element capacity");
        this.type = Objects.requireNonNull(type, "type");
        capacity = dataSize;
        buffer = MemoryUtil.memAlloc(Math.multiplyExact(sizeOfType(), dataSize));
        address = MemoryUtil.memAddress(buffer);
        this.order = buffer.order();
        data = new Object[dataSize];
    }

    public MappedBuffer(T[] data, Class<T> type) {
        Objects.requireNonNull(data, "data");
        this.type = Objects.requireNonNull(type, "type");
        capacity = data.length;
        buffer = MemoryUtil.memAlloc(Math.multiplyExact(sizeOfType(), data.length));
        address = MemoryUtil.memAddress(buffer);
        this.order = buffer.order();
        this.data = data;
    }

    public T getDataFromBuffer(int index) {
        return getData(slice(index));
    }

    public ByteBuffer slice(int index) {
        checkIndex(index);
        return buffer.slice(index * sizeOfType(), sizeOfType()).order(order);
    }

    public abstract T getData(ByteBuffer slice);

    public abstract void writeData(T value, int index);

    public abstract int sizeOfType();

    public void updateAll(T[] newData) {
        checkOpen("Buffer is closed");
        Objects.requireNonNull(newData, "newData");
        if (newData.length > capacity) {
            throw new IllegalArgumentException("New data size exceeds buffer capacity.");
        }
        System.arraycopy(newData, 0, data, 0, newData.length);
        for (int i = 0; i < newData.length; i++) {
            writeData(newData[i], i);
        }
    }

    public void updateRange(T[] newData, int offset) {
        checkOpen("Buffer is closed");
        Objects.requireNonNull(newData, "newData");
        if (offset < 0 || offset > capacity || newData.length > capacity - offset) {
            throw new IllegalArgumentException("New data size exceeds buffer capacity from the given offset.");
        }
        System.arraycopy(newData, 0, data, offset, newData.length);
        for (int i = 0; i < newData.length; i++) {
            writeData(newData[i], offset + i);
        }
    }

    public int bufferCapacity() {
        return buffer.capacity();
    }

    public int bufferPosition() {
        return buffer.position();
    }

    public int bufferLimit() {
        return buffer.limit();
    }

    public int bufferRemaining() {
        return buffer.remaining();
    }

    public int arrayCapacity() {
        return capacity;
    }

    protected final void checkIndex(int index) {
        checkOpen("Buffer is closed");
        if (index < 0 || index >= capacity) throw new IllegalArgumentException("Index is out of bounds.");
    }

    public void checkOpen(String operation) {
        if (isClosed) {
            throw new IllegalStateException(operation);
        }
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        isClosed = true;
        MemoryUtil.memFree(buffer);
        data = null;
    }

    public boolean isLittleEndian() {
        return order == ByteOrder.LITTLE_ENDIAN;
    }

     public boolean isBigEndian() {
        return order == ByteOrder.BIG_ENDIAN;
    }
}
