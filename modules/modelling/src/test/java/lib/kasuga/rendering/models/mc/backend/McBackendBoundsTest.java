package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McBackendBoundsTest {

    @Test void failedPoseSamplingRestoresTheCallersMatrixStack() throws Exception {
        try (var backend = new MCBackend(); var instance = fixture()) {
            var failure = new IllegalStateException("sample failed");
            instance.setPoseDriver(new lib.kasuga.rendering.models.uml.dynamic.PoseDriver() {
                @Override public void sample(float partialTick) { throw failure; }
            });
            var stack = new com.mojang.blaze3d.vertex.PoseStack(); stack.translate(2, 3, 4);
            var previous = new org.joml.Matrix4f(stack.last().pose());
            var renderable = new lib.kasuga.rendering.models.uml.backend.BackendContext<MCBridge, BackendInstance, MCBackendContext, MCBackend.BackendTransform>(
                    new MCBridge(), instance, ignored -> { throw new AssertionError("No GPU allocation expected"); }) {
                @Override public MCBackend.BackendTransform beforeRender(MCBackendContext context) { return null; }
            };
            var context = new MCBackendContext(null, stack, null, null, null, null,
                    new org.joml.Matrix4f(), new org.joml.Matrix4f(), 0, null, null) {
                @Override public float getPartialTickFraction() { return 0; }
            };
            assertSame(failure, assertThrows(IllegalStateException.class, () -> backend.render(renderable, context)));
            assertTrue(stack.clear(), "render failure must release its pushed pose");
            assertEquals(previous, stack.last().pose());
        }
    }

    @Test
    void ambientEnhancementIsNeutralizedForIris() {
        ModelInstance instance = fixture();
        instance.setAmbientLightEnhancement(2.25f);

        assertEquals(2.25f, MCBackend.effectiveAmbientLightEnhancement(instance, false));
        assertEquals(1f, MCBackend.effectiveAmbientLightEnhancement(instance, true));
    }

    @Test
    void cameraRelativeOriginRetainsSubBlockPrecisionAtTheWorldBorder() {
        Vector3f relative = MCBackend.cameraRelativeOrigin(
                new Vector3d(30_000_000.375, 96.125, -29_999_999.625),
                new Vec3(30_000_000.125, 95.875, -29_999_999.875));

        assertTrue(relative.equals(new Vector3f(0.25f), 0f),
                "double subtraction must happen before conversion to the float pose matrix");
    }

    @Test
    void freshlyConstructedInstanceEvaluatesAtTheBindPose() {
        ModelInstance instance = fixture();
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);

        assertTrue(MCBackend.scanEvaluatedBounds(instance, min, max),
                "the constructor performs an initial hierarchy evaluation");
        assertTrue(min.lengthSquared() < 1f && max.lengthSquared() < 1f,
                "an identity-pose two-bone chain stays near the origin");
    }

    @Test
    void boundsFollowPhysicallyDisplacedBonesInsteadOfTheBindPose() {
        ModelInstance instance = fixture();
        Bone root = instance.getModel().getSkeleton().getBones()[0];
        Bone child = instance.getModel().getSkeleton().getBones()[1];

        // Simulate a ragdoll tumble: physics writes LOCAL bone transforms that
        // drag the pose far away from the authored bind-pose volume.
        instance.getSkeletonInstance().transform(child,
                new Transform().translate(40f, -25f, 12f));
        instance.getSkeletonInstance().updateTransform();

        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        assertTrue(MCBackend.scanEvaluatedBounds(instance, min, max));

        assertTrue(max.x >= 40f && min.y <= -25f && max.z >= 12f,
                "the visibility box must cover the displaced bone (" + min + " .. " + max + ")");

        // The bind-pose-only box would have missed it entirely.
        assertFalse(min.x > 0f && max.x < 1f && min.y > -1f,
                "bounds must not stay confined to the bind-pose volume");
    }

    private static ModelInstance fixture() {
        Bone root = new Bone("root", new Transform(), null);
        Bone child = new Bone("child", new Transform(), null);
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        child.setChildren(new Bone[0]);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, null, null);
    }
}
