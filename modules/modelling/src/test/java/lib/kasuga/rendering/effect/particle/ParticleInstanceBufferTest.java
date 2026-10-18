package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleInstanceBufferTest {

    @Test
    void packsTransformColorVelocityAndAttributesWithoutPerInstanceViews() {
        ParticleInstanceBuffer buffer = new ParticleInstanceBuffer(1);
        buffer.beginWrite();
        buffer.put(
                42,
                new Transform().translate(1, 2, 3).scale(2, 3, 4),
                new Vector3f(0.1f, 0.2f, 0.3f),
                new Vector4f(0.4f, 0.5f, 0.6f, 0.7f),
                new float[]{8, 9},
                true
        );

        assertEquals(1, buffer.size());
        assertEquals(42, buffer.id(0));
        assertEquals(new Vector3f(1, 2, 3), buffer.position(0, new Vector3f()));
        assertEquals(new Vector3f(0.1f, 0.2f, 0.3f), buffer.velocity(0, new Vector3f()));
        assertEquals(new Vector4f(0.4f, 0.5f, 0.6f, 0.7f), buffer.color(0, new Vector4f()));
        assertEquals(2, buffer.attributeCount(0));
        assertEquals(9, buffer.attribute(0, 1));
        assertEquals(2, buffer.matrix(0, new Matrix4f()).getScale(new Vector3f()).x);
        assertTrue(buffer.visible(0));
        assertEquals(ParticleInstanceBuffer.STRIDE_BYTES, buffer.uploadBytes().remaining());
        assertTrue(buffer.uploadBytes().isReadOnly());
        assertSame(buffer.uploadBytes(), buffer.uploadBytes());

        buffer.setMatrix(0, new Matrix4f().translate(7, 8, 9));
        buffer.setVelocity(0, new Vector3f(3, 2, 1));
        buffer.setColor(0, new Vector4f(1, 0, 0, 0.25f));
        buffer.attribute(0, 1, 12);
        buffer.visible(0, false);
        buffer.remove(0);

        assertEquals(new Vector3f(7, 8, 9), buffer.position(0, new Vector3f()));
        assertEquals(new Vector3f(3, 2, 1), buffer.velocity(0, new Vector3f()));
        assertEquals(new Vector4f(1, 0, 0, 0.25f), buffer.color(0, new Vector4f()));
        assertEquals(12, buffer.attribute(0, 1));
        assertFalse(buffer.visible(0));
        assertTrue(buffer.removed(0));
    }

    @Test
    void growsOnlyWhenCapacityIsExceededAndResetsCountInPlace() {
        ParticleInstanceBuffer buffer = new ParticleInstanceBuffer(1);
        putIdentity(buffer, 1);
        putIdentity(buffer, 2);
        int grownCapacity = buffer.capacity();
        assertEquals(2, buffer.size());

        buffer.beginWrite();
        putIdentity(buffer, 3);

        assertEquals(1, buffer.size());
        assertEquals(grownCapacity, buffer.capacity());
        assertEquals(3, buffer.id(0));
    }

    @Test
    void rejectsAttributesThatCannotFitThePackedLayout() {
        ParticleInstanceBuffer buffer = new ParticleInstanceBuffer(1);
        assertThrows(IllegalArgumentException.class, () -> buffer.put(
                1, new Transform(), new Vector3f(), new Vector4f(),
                new float[ParticleInstanceBuffer.MAX_ATTRIBUTES + 1], true
        ));
    }

    @Test
    void depthSorterReturnsStableBackToFrontInstanceOrder() {
        ParticleInstanceBuffer buffer = new ParticleInstanceBuffer(3);
        putAt(buffer, 1, 2);
        putAt(buffer, 2, 10);
        putAt(buffer, 3, 5);
        ParticleDepthSorter sorter = new ParticleDepthSorter();

        sorter.sortBackToFront(buffer, 0, 0, 0);

        assertEquals(2, buffer.id(sorter.indexAt(0)));
        assertEquals(3, buffer.id(sorter.indexAt(1)));
        assertEquals(1, buffer.id(sorter.indexAt(2)));
        ParticleInstanceBuffer sorted = new ParticleInstanceBuffer(1);
        sorted.copyFrom(buffer, sorter);
        assertEquals(2, sorted.id(0));
        assertEquals(3, sorted.id(1));
        assertEquals(1, sorted.id(2));
        int capacity = sorter.capacity();

        sorter.sortBackToFront(buffer, 12, 0, 0);
        assertEquals(1, buffer.id(sorter.indexAt(0)));
        assertEquals(3, buffer.id(sorter.indexAt(1)));
        assertEquals(2, buffer.id(sorter.indexAt(2)));
        assertEquals(capacity, sorter.capacity());
    }

    private static void putIdentity(ParticleInstanceBuffer buffer, long id) {
        buffer.put(id, new Transform(), new Vector3f(), new Vector4f(1), new float[0], true);
    }

    private static void putAt(ParticleInstanceBuffer buffer, long id, float x) {
        buffer.put(
                id,
                new Transform().translate(x, 0, 0),
                new Vector3f(),
                new Vector4f(1),
                new float[0],
                true
        );
    }
}
