package lib.kasuga.rendering.models.uml.framework.buffer;

import java.nio.ByteBuffer;

/**
 * Fixed-capacity, thread-owned CPU storage. Indices and offsets count elements;
 * sizeOfType and bufferCapacity count bytes. Bulk updates do not change capacity.
 * Byte views borrow the allocation and must not outlive it. close is idempotent;
 * subsequent reads/writes fail. Implementations define the element packing.
 */
public interface TypedBuffer<T> extends AutoCloseable {
    Class<T> getType();
    boolean isClosed();
    int sizeOfType();
    int arrayCapacity();
    int bufferCapacity();
    ByteBuffer slice(int index);
    T getDataFromBuffer(int index);
    void writeData(T value, int index);
    void updateAll(T[] data);
    void updateRange(T[] data, int offset);
}
