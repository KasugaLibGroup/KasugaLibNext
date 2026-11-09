package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FluidModelCoordinatesTest {
    @Test void fluidVerticesAgreeAcrossPositiveAndNegativeSectionBoundaries() {
        for (int x : new int[]{-17, -16, -1, 0, 15, 16, 17, 16384}) {
            var pose = new PoseStack(); pose.translate(x, -17, 16);
            var output = new Capture();
            var fluid = new BlockModelSource.FluidVertices(output, pose.last(), x & 15, 15, 0);
            fluid.addVertex((x & 15) + .25f, 15.75f, .5f);
            assertEquals(x + .25f, output.position.x, 1e-5);
            assertEquals(-16.25f, output.position.y, 1e-5);
            assertEquals(16.5f, output.position.z, 1e-5);
        }
    }

    @Test void modelRotationAppliesOnceToBothFluidPositionAndNormal() {
        var pose = new PoseStack(); pose.mulPose(new Quaternionf().rotateZ((float) Math.PI / 2));
        var output = new Capture();
        var fluid = new BlockModelSource.FluidVertices(output, pose.last(), 0, 0, 0);
        fluid.addVertex(2, 1, 0).setNormal(0, 1, 0);
        assertEquals(-1, output.position.x, 1e-5); assertEquals(2, output.position.y, 1e-5);
        assertEquals(-1, output.normal.x, 1e-5); assertEquals(0, output.normal.y, 1e-5);
    }

    private static final class Capture implements VertexConsumer {
        private final Vector3f position = new Vector3f(), normal = new Vector3f();
        @Override public VertexConsumer addVertex(float x, float y, float z) { position.set(x, y, z); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { normal.set(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
    }
}
