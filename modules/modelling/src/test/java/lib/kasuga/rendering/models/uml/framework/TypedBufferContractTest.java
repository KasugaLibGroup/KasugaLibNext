package lib.kasuga.rendering.models.uml.framework;

import lib.kasuga.rendering.models.uml.backend.cpu.MappedV4fBuffer;
import lib.kasuga.rendering.models.uml.framework.buffer.TypedBuffer;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypedBufferContractTest {
    @Test
    void shortBulkUpdatesPreserveCapacityAndElementPacking() throws Exception {
        try (TypedBuffer<Vector4f> buffer = new MappedV4fBuffer(3)) {
            Vector4f first = new Vector4f(1.25f, -2.5f, 3.75f, 4.5f);
            Vector4f last = new Vector4f(5, 6, 7, 8);
            buffer.updateAll(new Vector4f[]{first});
            assertEquals(3, buffer.arrayCapacity());
            assertEquals(3 * 4 * Float.BYTES, buffer.bufferCapacity());
            buffer.updateRange(new Vector4f[]{last}, 2);
            assertEquals(first, buffer.getDataFromBuffer(0));
            assertEquals(last, buffer.getDataFromBuffer(2));
            buffer.updateRange(new Vector4f[0], 3);
            assertEquals(last, buffer.getDataFromBuffer(2));
        }
    }

    @Test
    void invalidRangesNeverReachNativeMemoryAndCloseIsIdempotent() throws Exception {
        TypedBuffer<Vector4f> buffer = new MappedV4fBuffer(1);
        try {
            assertThrows(IllegalArgumentException.class, () -> buffer.writeData(new Vector4f(), -1));
            assertThrows(IllegalArgumentException.class, () -> buffer.writeData(new Vector4f(), 1));
            assertThrows(IllegalArgumentException.class, () -> buffer.updateRange(new Vector4f[]{new Vector4f()}, Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> buffer.updateAll(new Vector4f[2]));
        } finally {
            buffer.close();
        }
        buffer.close();
        assertTrue(buffer.isClosed());
        assertThrows(IllegalStateException.class, () -> buffer.slice(0));
        assertThrows(IllegalStateException.class, () -> buffer.writeData(new Vector4f(), 0));
        assertThrows(IllegalStateException.class, () -> buffer.updateAll(new Vector4f[0]));
    }
}
