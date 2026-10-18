package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reusable packed direct storage consumed by one particle batch renderer.
 *
 * <p>The buffer is rebuilt in place for each render traversal, so instance count does not create
 * per-frame Java objects. Capacity only grows and is normally established during warm-up.</p>
 */
public final class ParticleInstanceBuffer {
    public static final int MAX_ATTRIBUTES = 8;

    private static final int ID = 0;
    public static final int MATRIX_OFFSET_BYTES = 8;
    public static final int COLOR_OFFSET_BYTES = 84;
    public static final int STRIDE_BYTES = 144;

    private static final int MATRIX = MATRIX_OFFSET_BYTES;
    private static final int VELOCITY = 72;
    private static final int COLOR = COLOR_OFFSET_BYTES;
    private static final int ATTRIBUTE_COUNT = 100;
    private static final int ATTRIBUTES = 104;
    private static final int FLAGS = 136;
    private static final int STRIDE = STRIDE_BYTES;
    private static final int VISIBLE = 1;
    private static final int REMOVED = 2;

    private ByteBuffer storage;
    private ByteBuffer uploadView;
    private int count;

    ParticleInstanceBuffer(int initialCapacity) {
        if (initialCapacity <= 0) throw new IllegalArgumentException("initialCapacity must be positive");
        storage = allocate(initialCapacity);
        uploadView = storage.asReadOnlyBuffer();
    }

    void beginWrite() {
        count = 0;
    }

    void put(long id, Transform transform, Vector3f velocity, Vector4f color,
             float[] attributes, boolean visible) {
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.length > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(
                    "Particle instance attributes exceed " + MAX_ATTRIBUTES + ": " + attributes.length
            );
        }
        ensureCapacity(count + 1);
        int base = count * STRIDE;
        Matrix4f matrix = transform.transform();
        storage.putLong(base + ID, id);
        putMatrix(base + MATRIX, matrix);
        storage.putFloat(base + VELOCITY, velocity.x);
        storage.putFloat(base + VELOCITY + 4, velocity.y);
        storage.putFloat(base + VELOCITY + 8, velocity.z);
        storage.putFloat(base + COLOR, color.x);
        storage.putFloat(base + COLOR + 4, color.y);
        storage.putFloat(base + COLOR + 8, color.z);
        storage.putFloat(base + COLOR + 12, color.w);
        storage.putInt(base + ATTRIBUTE_COUNT, attributes.length);
        for (int index = 0; index < MAX_ATTRIBUTES; index++) {
            storage.putFloat(base + ATTRIBUTES + index * 4, index < attributes.length ? attributes[index] : 0);
        }
        storage.putInt(base + FLAGS, visible ? VISIBLE : 0);
        count++;
    }

    void copyFrom(ParticleInstanceBuffer source) {
        Objects.requireNonNull(source, "source");
        ensureCapacity(source.count);
        int bytes = source.count * STRIDE;
        ByteBuffer input = source.storage.duplicate();
        input.position(0).limit(bytes);
        ByteBuffer output = storage.duplicate();
        output.position(0).limit(bytes);
        output.put(input);
        count = source.count;
    }

    void copyFrom(ParticleInstanceBuffer source, ParticleDepthSorter order) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(order, "order");
        if (order.size() != source.count) {
            throw new IllegalArgumentException(
                    "Sort order size " + order.size() + " != instance count " + source.count
            );
        }
        ensureCapacity(source.count);
        ByteBuffer input = source.storage.duplicate();
        ByteBuffer output = storage.duplicate();
        for (int sortedIndex = 0; sortedIndex < source.count; sortedIndex++) {
            int sourceOffset = order.indexAt(sortedIndex) * STRIDE;
            input.clear().position(sourceOffset).limit(sourceOffset + STRIDE);
            output.position(sortedIndex * STRIDE);
            output.put(input);
        }
        count = source.count;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public long id(int index) {
        return storage.getLong(base(index) + ID);
    }

    public Matrix4f matrix(int index, Matrix4f destination) {
        Objects.requireNonNull(destination, "destination");
        int offset = base(index) + MATRIX;
        return destination
                .m00(storage.getFloat(offset))
                .m01(storage.getFloat(offset + 4))
                .m02(storage.getFloat(offset + 8))
                .m03(storage.getFloat(offset + 12))
                .m10(storage.getFloat(offset + 16))
                .m11(storage.getFloat(offset + 20))
                .m12(storage.getFloat(offset + 24))
                .m13(storage.getFloat(offset + 28))
                .m20(storage.getFloat(offset + 32))
                .m21(storage.getFloat(offset + 36))
                .m22(storage.getFloat(offset + 40))
                .m23(storage.getFloat(offset + 44))
                .m30(storage.getFloat(offset + 48))
                .m31(storage.getFloat(offset + 52))
                .m32(storage.getFloat(offset + 56))
                .m33(storage.getFloat(offset + 60));
    }

    public void setMatrix(int index, Matrix4f value) {
        putMatrix(base(index) + MATRIX, Objects.requireNonNull(value, "value"));
    }

    public Vector3f position(int index, Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        int offset = base(index) + MATRIX;
        return destination.set(
                storage.getFloat(offset + 48),
                storage.getFloat(offset + 52),
                storage.getFloat(offset + 56)
        );
    }

    public Vector3f velocity(int index, Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        int offset = base(index) + VELOCITY;
        return destination.set(
                storage.getFloat(offset),
                storage.getFloat(offset + 4),
                storage.getFloat(offset + 8)
        );
    }

    public void setVelocity(int index, Vector3f value) {
        Objects.requireNonNull(value, "value");
        int offset = base(index) + VELOCITY;
        storage.putFloat(offset, value.x);
        storage.putFloat(offset + 4, value.y);
        storage.putFloat(offset + 8, value.z);
    }

    public Vector4f color(int index, Vector4f destination) {
        Objects.requireNonNull(destination, "destination");
        int offset = base(index) + COLOR;
        return destination.set(
                storage.getFloat(offset),
                storage.getFloat(offset + 4),
                storage.getFloat(offset + 8),
                storage.getFloat(offset + 12)
        );
    }

    public void setColor(int index, Vector4f value) {
        Objects.requireNonNull(value, "value");
        int offset = base(index) + COLOR;
        storage.putFloat(offset, value.x);
        storage.putFloat(offset + 4, value.y);
        storage.putFloat(offset + 8, value.z);
        storage.putFloat(offset + 12, value.w);
    }

    public int attributeCount(int index) {
        return storage.getInt(base(index) + ATTRIBUTE_COUNT);
    }

    public float attribute(int index, int attribute) {
        int count = attributeCount(index);
        if (attribute < 0 || attribute >= count) {
            throw new IndexOutOfBoundsException("attribute=" + attribute + ", count=" + count);
        }
        return storage.getFloat(base(index) + ATTRIBUTES + attribute * 4);
    }

    public void attribute(int index, int attribute, float value) {
        int count = attributeCount(index);
        if (attribute < 0 || attribute >= count) {
            throw new IndexOutOfBoundsException("attribute=" + attribute + ", count=" + count);
        }
        storage.putFloat(base(index) + ATTRIBUTES + attribute * 4, value);
    }

    public boolean visible(int index) {
        return (storage.getInt(base(index) + FLAGS) & VISIBLE) != 0;
    }

    public void visible(int index, boolean value) {
        int offset = base(index) + FLAGS;
        int flags = storage.getInt(offset);
        storage.putInt(offset, value ? flags | VISIBLE : flags & ~VISIBLE);
    }

    public boolean removed(int index) {
        return (storage.getInt(base(index) + FLAGS) & REMOVED) != 0;
    }

    public void remove(int index) {
        int offset = base(index) + FLAGS;
        storage.putInt(offset, storage.getInt(offset) | REMOVED);
    }

    public int capacity() {
        return storage.capacity() / STRIDE;
    }

    /**
     * Read-only direct view of the active packed bytes for a render backend upload.
     *
     * <p>The returned object is reused and remains owned by this buffer. Consumers must read it
     * synchronously and must not retain it after the current render callback.</p>
     */
    public ByteBuffer uploadBytes() {
        uploadView.position(0).limit(count * STRIDE);
        return uploadView;
    }

    private int base(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index=" + index + ", count=" + count);
        }
        return index * STRIDE;
    }

    private void ensureCapacity(int required) {
        if (required <= capacity()) return;
        int next = Math.max(required, capacity() * 2);
        ByteBuffer replacement = allocate(next);
        ByteBuffer source = storage.duplicate();
        source.clear();
        replacement.put(source);
        replacement.clear();
        storage = replacement;
        uploadView = storage.asReadOnlyBuffer();
    }

    private static ByteBuffer allocate(int capacity) {
        return ByteBuffer.allocateDirect(Math.multiplyExact(capacity, STRIDE))
                .order(ByteOrder.nativeOrder());
    }

    private void putMatrix(int offset, Matrix4f matrix) {
        storage.putFloat(offset, matrix.m00());
        storage.putFloat(offset + 4, matrix.m01());
        storage.putFloat(offset + 8, matrix.m02());
        storage.putFloat(offset + 12, matrix.m03());
        storage.putFloat(offset + 16, matrix.m10());
        storage.putFloat(offset + 20, matrix.m11());
        storage.putFloat(offset + 24, matrix.m12());
        storage.putFloat(offset + 28, matrix.m13());
        storage.putFloat(offset + 32, matrix.m20());
        storage.putFloat(offset + 36, matrix.m21());
        storage.putFloat(offset + 40, matrix.m22());
        storage.putFloat(offset + 44, matrix.m23());
        storage.putFloat(offset + 48, matrix.m30());
        storage.putFloat(offset + 52, matrix.m31());
        storage.putFloat(offset + 56, matrix.m32());
        storage.putFloat(offset + 60, matrix.m33());
    }
}
