package lib.kasuga.rendering.models.mc.backend.vbuffer;

import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class IrisVertexAttributesTest {
    @Test void dirtyVertexInsideTriangleUsesTheWholePrimitiveAcrossWorkerBoundary() {
        var source = ByteBuffer.allocate(10002 * 12);
        source.putFloat(9999 * 12 + 4, .2f).putFloat(9999 * 12 + 8, .1f);
        source.putFloat(10000 * 12 + 4, .6f).putFloat(10000 * 12 + 8, .4f);
        source.putFloat(10001 * 12 + 4, .7f).putFloat(10001 * 12 + 8, 1f);
        var midpoint = new Vector2f();
        IrisVertexBuffer.midpointUv(source, 12, 4, 10000, 3, 10002, midpoint);
        assertEquals(.5f, midpoint.x, 1e-6);
        assertEquals(.5f, midpoint.y, 1e-6);
        assertEquals(0, source.position());
    }
    @Test void quadCenterAndSignedTangentHandednessArePreserved() {
        var source = ByteBuffer.allocate(4 * 8);
        source.asFloatBuffer().put(new float[]{.1f, .2f, .9f, .2f, .9f, .8f, .1f, .8f});
        var midpoint = new Vector2f();
        IrisVertexBuffer.midpointUv(source, 8, 0, 2, 4, 4, midpoint);
        assertEquals(.5f, midpoint.x, 1e-6); assertEquals(.5f, midpoint.y, 1e-6);
        assertEquals((byte) 127, IrisVertexBuffer.normalizedTangent(1));
        assertEquals((byte) -127, IrisVertexBuffer.normalizedTangent(-1));
        assertEquals((byte) 127, IrisVertexBuffer.normalizedTangent(2));
        assertEquals((byte) -127, IrisVertexBuffer.normalizedTangent(-2));
    }
}
