package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DummyModelInstanceTest {
    @Test
    void emptyRigNeverReachesBackendContextOrBridge() throws Exception {
        try (var group = new DummyModelInstance()) {
            assertEquals(0, group.getModel().getVertices().length);
            assertEquals(0, group.getModel().getMeshes().length);
            assertEquals(0, group.getModel().getMaterialSet().getMaterials().length);
            assertEquals(0, group.getModel().getMaterialSet().getTextures().length);
            assertEquals(1, group.getModel().getBones().length);
            assertFalse(group.getModel().isRenderable());
            AtomicInteger calls = new AtomicInteger();
            @SuppressWarnings("unchecked")
            Bridge<Object> bridge = (Bridge<Object>) Proxy.newProxyInstance(Bridge.class.getClassLoader(),
                    new Class<?>[]{Bridge.class}, (proxy, method, args) -> {
                        fail("dummy must not invoke bridge: " + method.getName()); return null;
                    });
            try (var backend = new Backend<Bridge<Object>, Object, Void, Void>() {
                @Override protected BackendContext<Bridge<Object>, Object, Void, Void> createContext(
                        Bridge<Object> ignored, ModelInstance model) {
                    calls.incrementAndGet();
                    throw new AssertionError("dummy must not allocate a render context");
                }
                @Override public void render(BackendContext<Bridge<Object>, Object, Void, Void> context, Void ignored) {
                    fail("dummy must not draw");
                }
            }) {
                backend.add(group, bridge, group);
                backend.renderAllObjects(null);
                assertFalse(backend.contains(group));
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void runtimeAnchorsComposeFullTrsAndDefensivelyCopyOffsets() {
        try (var group = new DummyModelInstance(); var child = new DummyModelInstance()) {
            Transform anchor = new Transform().translate(2, 1, -1).rotate(10, 20, 30, true);
            Transform offset = new Transform().translate(0, 3, 0).scale(1, 2, 1);
            Transform expectedAnchor = anchor.copy(), expectedOffset = offset.copy();
            group.defineAnchor("hand", anchor);
            var binding = group.attach("hand", child, offset);
            assertMatrix(expectedAnchor.copy().mul(expectedOffset), child.getSkeletonInstance().getTransform());
            anchor.translate(100, 0, 0);
            offset.translate(100, 0, 0);
            Transform root = new Transform().translate(10, 20, 30).rotate(0, 90, 0, true).scale(2, 2, 2);
            group.getSkeletonInstance().transformRoot(root);
            group.tick(0);
            assertMatrix(root.copy().mul(expectedAnchor).mul(expectedOffset), child.getSkeletonInstance().getTransform());
            group.setAnchorTransform("hand", new Transform().translate(-2, 0, 0));
            group.tick(0);
            assertMatrix(root.copy().mul(new Transform().translate(-2, 0, 0)).mul(expectedOffset),
                    child.getSkeletonInstance().getTransform());
            binding.setOffset(new Transform());
            group.tick(0);
            assertMatrix(root.copy().mul(new Transform().translate(-2, 0, 0)), child.getSkeletonInstance().getTransform());
        }
    }

    @Test
    void childSamplingResolvesNestedParentsWithoutRenderingThem() {
        try (var outer = new DummyModelInstance(); var inner = new DummyModelInstance(); var child = new DummyModelInstance()) {
            outer.defineAnchor("nested", new Transform().translate(2, 0, 0));
            inner.defineAnchor("leaf", new Transform().translate(0, 3, 0));
            outer.attach("nested", inner);
            inner.attach("leaf", child);
            outer.getSkeletonInstance().transformRoot(new Transform().translate(10, 0, 0));
            child.sample(0.5f);
            child.update();
            assertMatrix(new Transform().translate(12, 3, 0), child.getSkeletonInstance().getTransform());
        }
    }

    @Test
    void farWorldCoordinatesKeepSubBlockOffsetsEvenWithAnUnrelatedChildOrigin() {
        try (var group = new DummyModelInstance(); var child = new DummyModelInstance()) {
            group.getSkeletonInstance().enableFloatingOrigin(new Vector3d(30_000_000.125, 80, -30_000_000.25));
            child.getSkeletonInstance().enableFloatingOrigin(new Vector3d(-30_000_000, 0, 30_000_000));
            group.defineAnchor("point", new Transform().translate(0.125f, 0.5f, 0.125f));
            group.attach("point", child);
            group.tick(0);
            var world = child.getSkeletonInstance().getWorldRootPosition();
            assertEquals(30_000_000.25, world.x, 1e-6);
            assertEquals(80.5, world.y, 1e-6);
            assertEquals(-30_000_000.125, world.z, 1e-6);
        }
    }

    @Test
    void rootOwnershipIsExclusiveAndChildBoneAnimationSurvivesGrouping() {
        try (var group = new DummyModelInstance(); var child = new DummyModelInstance()) {
            group.defineAnchor("a", new Transform().translate(1, 0, 0));
            RootTransformDriver existing = partialTick -> {};
            child.setRootTransformDriver(existing);
            assertThrows(IllegalStateException.class, () -> group.attach("a", child));
            assertSame(existing, child.getRootTransformDriver());
            child.setRootTransformDriver(null);
            child.setAnimationRenderPort(context -> {});
            assertThrows(IllegalStateException.class, () -> group.attach("a", child));
            child.setAnimationRenderPort(null);
            group.attach("a", child);
            ModelPoseBinding.install(child, new PoseDriver() {
                @Override public void sample(float partialTick) {
                    child.getSkeletonInstance().transform("root", new Transform().translate(0, partialTick * 2, 0));
                }
            });
            group.getSkeletonInstance().transformRoot(new Transform().translate(10, 0, 0));
            child.sample(.5f);
            child.update();
            assertMatrix(new Transform().translate(11, 0, 0), child.getSkeletonInstance().getTransform());
            assertMatrix(new Transform().translate(11, 1, 0), child.getSkeletonInstance().getAbsoluteTransforms()
                    .get(child.getModel().getSkeleton().getRoot()));
        }
    }

    @Test
    void cyclesMultipleParentsAndOtherRootOwnersAreRejected() {
        try (var first = new DummyModelInstance(); var second = new DummyModelInstance(); var third = new DummyModelInstance()) {
            for (var group : new DummyModelInstance[]{first, second, third}) group.defineAnchor("a", new Transform());
            assertThrows(IllegalArgumentException.class, () -> first.attach("a", first));
            first.attach("a", second);
            second.attach("a", third);
            assertThrows(IllegalArgumentException.class, () -> third.attach("a", first));
            assertThrows(IllegalStateException.class, () -> first.attach("a", third));
            assertEquals(1, first.attachmentCount());
            assertEquals(1, second.attachmentCount());
            assertThrows(IllegalArgumentException.class, () -> first.attach("missing", third));
        }
    }

    @Test
    void detachAndCloseReleaseBindingsWithoutClosingChildren() {
        var first = new DummyModelInstance();
        var second = new DummyModelInstance();
        var child = new DummyModelInstance();
        try {
            first.defineAnchor("a", new Transform().translate(1, 0, 0));
            second.defineAnchor("a", new Transform().translate(2, 0, 0));
            var binding = first.attach("a", child);
            first.tick(0);
            binding.close(); binding.close();
            assertEquals(0, first.attachmentCount());
            assertNull(child.getRootTransformDriver());
            first.getSkeletonInstance().transformRoot(new Transform().translate(100, 0, 0));
            first.tick(0);
            assertMatrix(new Transform().translate(1, 0, 0), child.getSkeletonInstance().getTransform());
            second.attach("a", child);
            second.close(); second.close();
            assertFalse(child.isClosed());
            assertNull(child.getRootTransformDriver());
            first.attach("a", child);
            child.close();
            assertEquals(0, first.attachmentCount());
            assertThrows(IllegalStateException.class, () -> first.attach("a", child));
            assertThrows(IllegalStateException.class, () -> second.defineAnchor("late", new Transform()));
        } finally { first.close(); second.close(); child.close(); }
    }

    @Test
    void invalidAnchorsAndOffsetsDoNotPolluteTheRig() {
        try (var group = new DummyModelInstance(); var other = new DummyModelInstance()) {
            group.defineAnchor("中文锚点", new Transform());
            assertNull(other.anchorTransform("中文锚点"));
            assertThrows(IllegalArgumentException.class, () -> group.defineAnchor("中文锚点", new Transform()));
            assertThrows(IllegalArgumentException.class, () -> group.defineAnchor(" ", new Transform()));
            assertThrows(IllegalArgumentException.class, () -> group.defineAnchor("bad", new Transform().translate(Float.NaN, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> group.setAnchorTransform("missing", new Transform()));
            assertThrows(IllegalArgumentException.class, () -> group.attach("中文锚点", other,
                    new Transform().translate(Float.POSITIVE_INFINITY, 0, 0)));
            assertEquals(0, group.attachmentCount());
            group.attach("中文锚点", other, new Transform().scale(0, 0, 0));
            group.tick(0);
            assertTrue(other.getSkeletonInstance().getTransform().transform().isFinite());
            assertTrue(other.getSkeletonInstance().getTransform().normal().isFinite());
        }
    }

    private static void assertMatrix(Transform expected, Transform actual) {
        assertTrue(expected.transform().equals(actual.transform(), 1e-4f),
                () -> "expected " + expected.transform() + " but was " + actual.transform());
        assertTrue(actual.normal().isFinite());
    }
}
