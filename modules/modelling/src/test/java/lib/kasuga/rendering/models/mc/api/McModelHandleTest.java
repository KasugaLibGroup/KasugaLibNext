package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McModelHandleTest {
    @Test
    void handleCanBeDestroyedBeforeGlobalPipelinesInitialize() {
        McModelHandle handle = McModelHandle.of(
                ResourceLocation.fromNamespaceAndPath("test", "missing.obj"), null,
                ResourceLocation.fromNamespaceAndPath("test", "early"), null);
        assertFalse(handle.mount());
        handle.destroy(); assertFalse(handle.mount());
    }
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("test", "model");
    private static final ResourceLocation INSTANCE =
            ResourceLocation.fromNamespaceAndPath("test", "instance");

    @Test
    void poseOperationsAreBufferedUntilTheResourcePublishes() {
        AtomicBoolean published = new AtomicBoolean(false);
        AtomicInteger bindCount = new AtomicInteger();
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> {
                    if (!published.get()) return null;
                    bindCount.incrementAndGet();
                    return fixture(pose);
                },
                loc -> null);

        // Resource unpublished: setters buffer, mount fails.
        assertFalse(handle.mount());
        handle.setPos(1f, 2f, 3f);
        assertFalse(handle.isMounted());

        published.set(true);
        assertTrue(handle.mount());
        assertTrue(handle.isMounted());
        assertEquals(1, bindCount.get(), "binding must happen exactly once");

        assertEquals(new Vec3(1d, 2d, 3d), handle.getPos(),
                "the pose buffered before publication must be applied on mount");

        // Mounted edits flush immediately and survive remount cycles.
        handle.move(new Vec3(0.5f, 0f, 0f));
        assertEquals(new Vec3(1.5d, 2d, 3d), handle.getPos());
    }

    @Test
    void worldPositionApiSurvivesFloatingOriginActivation() {
        ModelInstance instance = fixture(new Transform().translate(100f, 20f, -30f));
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());

        instance.getSkeletonInstance().enableFloatingOrigin();
        assertEquals(new Vec3(100d, 20d, -30d), handle.getPos());

        handle.setPos(101.25, 21.5, -29.75);
        assertEquals(new Vec3(101.25, 21.5, -29.75), handle.getPos());
        assertEquals(new org.joml.Vector3f(1.25f, 1.5f, 0.25f),
                instance.getSkeletonInstance().getTransform().getPosition());

        handle.rotateByDegrees(0f, 45f, 0f);
        assertEquals(new Vec3(101.25, 21.5, -29.75), handle.getPos(),
                "rotation must preserve the exact world-space root position");
    }

    @Test
    void mountIsIdempotentAndReusesTheSameInstance() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());
        assertTrue(handle.mount());
        assertSame(instance, handle.instance());
    }

    @Test
    void ambientLightEnhancementIsBufferedAndCanBeDisabled() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);

        handle.setAmbientLightEnhancement(2.25f);
        assertEquals(2.25f, handle.ambientLightEnhancement());
        assertTrue(handle.mount());
        assertEquals(2.25f, instance.getAmbientLightEnhancement());

        handle.disableAmbientLightEnhancement();
        assertEquals(1f, instance.getAmbientLightEnhancement());
        assertThrows(IllegalArgumentException.class,
                () -> handle.setAmbientLightEnhancement(Float.NaN));
    }

    @Test
    void vanillaSchedulingGatesVisibilityThroughMarks() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());
        handle.scheduleVanillaRenderer();

        assertFalse(handle.shown(), "no mark yet — vanilla culled the host this frame");
        handle.markRenderedThisFrame();
        assertTrue(handle.shown(), "the renderer ran, so the model must render");
        ModelRenderScheduler.flipFrame();
        assertTrue(handle.shown());
        ModelRenderScheduler.flipFrame();
        assertFalse(handle.shown(), "next frame without a fresh mark is culled again");

        handle.scheduleAlways();
        assertTrue(handle.shown());
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void manualHideShowOverridesEverythingElse() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());
        handle.hide();
        assertFalse(handle.shown());
        handle.markRenderedThisFrame();
        assertFalse(handle.shown(), "manual hide wins over vanilla marks");
        handle.show();
        assertTrue(handle.shown());
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void unmountDropsStateButDestroyRemovesTheInstance() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());
        PoseDriver driver = new PoseDriver() {};
        handle.setAnimationDriver(driver);
        assertSame(instance, handle.instance());
        assertSame(driver, handle.instance().getPoseDriver());
        assertTrue(handle.unmount());
        assertNull(handle.instance());
        assertFalse(handle.unmount(), "unmount on an unmounted handle is a no-op");

        handle.destroy();
        assertFalse(handle.isMounted());
        assertThrows(IllegalStateException.class, handle::tickLoop);
    }

    private static ModelInstance fixture(Transform rootPose) {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root,
                new Anchor[0], null, rootPose == null ? new Transform() : rootPose.copy());
        Model model = new Model(new Vertex[0], new Mesh[0], new Bone[]{root},
                skeleton, new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        if (rootPose != null) instance.getSkeletonInstance().transformRoot(rootPose.copy());
        return instance;
    }
}
