package lib.kasuga.rendering.models.uml.framework;

import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.framework.render.RenderBackend;
import lib.kasuga.rendering.models.uml.framework.render.RenderContext;
import lib.kasuga.rendering.models.uml.framework.render.RenderableFactory;
import lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackendContractTest {
    @Test
    void backendCreatesContextsWithoutBridgeRegistryAndReleasesReplacement() throws Exception {
        Resource first = new Resource(), second = new Resource();
        Adapter adapter = new Adapter(instance -> first);
        ModelInstance instance = instance();
        try (RenderBackend<Adapter, BackendContext<Adapter, Resource, Void, Void>, Void> backend = new TestBackend()) {
            backend.add("slot", adapter, instance);
            BackendContext<Adapter, Resource, Void, Void> old = backend.getRenderingObjects().get("slot");
            assertSame(first, old.apply());
            assertSame(first, old.apply());
            assertThrows(UnsupportedOperationException.class, () -> backend.getRenderingObjects().clear());
            adapter.factory = model -> second;
            backend.add("slot", adapter, instance);
            assertEquals(1, first.closes);
            assertEquals(1, ((TestBackend) backend).released.size());
            assertTrue(old.isClosed());
            assertThrows(IllegalStateException.class, old::apply);
            assertTrue(backend.remove("slot"));
            assertFalse(backend.remove("slot"));
            assertEquals(1, second.closes);
        }
    }

    @Test
    void failedPreparationKeepsPreviousMountAndClosesUnpublishedContext() throws Exception {
        Resource resource = new Resource();
        Adapter adapter = new Adapter(instance -> resource);
        ModelInstance instance = instance();
        try (TestBackend backend = new TestBackend()) {
            backend.add("slot", adapter, instance);
            Context old = backend.lastCreated;
            adapter.factory = model -> { throw new IllegalStateException("prepare"); };
            assertThrows(IllegalStateException.class, () -> backend.add("slot", adapter, instance));
            assertSame(old, backend.getRenderingObjects().get("slot"));
            assertTrue(backend.lastCreated.isClosed());
            assertFalse(old.isClosed());
            assertEquals(0, resource.closes);
        }
        assertEquals(1, resource.closes);
    }

    @Test
    void shutdownAttemptsEveryReleaseAndReportsFailures() throws Exception {
        Resource first = new Resource(), second = new Resource();
        first.fail = second.fail = true;
        TestBackend backend = new TestBackend();
        backend.add("first", new Adapter(instance -> first), instance());
        backend.add("second", new Adapter(instance -> second), instance());
        Exception failure = assertThrows(IllegalStateException.class, backend::close);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        assertTrue(backend.isClosed());
        assertTrue(backend.getRenderingObjects().isEmpty());
        backend.close();
        assertThrows(IllegalStateException.class, () -> backend.add("third", new Adapter(instance -> first), instance()));
        assertThrows(IllegalStateException.class, () -> backend.renderAllObjects(null));
    }

    @Test
    void contextIsLazyAndDoesNotRecreateResourcesForPoseChanges() throws Exception {
        int[] creations = {0};
        Resource resource = new Resource();
        Adapter adapter = new Adapter(instance -> { creations[0]++; return resource; });
        RenderContext<Resource, Void, Void> context = new Context(adapter, instance());
        assertEquals(0, creations[0]);
        context.beforeRender(null);
        assertEquals(0, creations[0]);
        context.apply();
        context.getModelInstance().getSkeletonInstance().tick();
        context.apply();
        assertEquals(1, creations[0]);
        context.close();
        context.close();
        assertEquals(1, resource.closes);
    }

    @Test
    void pipelineRemovalReleasesEveryBackendAndRuntimeDespiteReleaseFailures() throws Exception {
        Resource first = new Resource(), second = new Resource();
        first.fail = second.fail = true;
        TestBackend a = new TestBackend(), b = new TestBackend();
        Adapter adapter = new Adapter(model -> first);
        ModelPipeLine<Object, Resource, String, String, String> pipeline = pipeline(adapter, a, b);
        pipeline.publishModels(Map.of("model", instance().getModel()));
        ModelInstance instance = pipeline.createInstance("model", "instance", null, null, null);
        instance.setPoseDriver(new PoseDriver() {});
        pipeline.addToRenderer("model", "instance", "bridge", "a");
        adapter.factory = model -> second;
        pipeline.addToRenderer("model", "instance", "bridge", "b");
        pipeline.setRendering("model", "instance", false);
        assertThrows(IllegalStateException.class, () -> pipeline.removeInstance("model", "instance"));
        assertTrue(a.getRenderingObjects().isEmpty());
        assertTrue(b.getRenderingObjects().isEmpty());
        assertFalse(pipeline.hasInstance("model", "instance"));
        assertNull(instance.getPoseDriver());
        assertTrue(ModelRenderScheduling.scheduler().shouldRender(instance));
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        a.close(); b.close();
    }

    @Test
    void resourceReloadRetiresAllStaleInstancesEvenWhenOneReleaseFails() throws Exception {
        List<Resource> resources = new ArrayList<>();
        Adapter adapter = new Adapter(model -> {
            Resource resource = new Resource();
            resource.fail = true;
            resources.add(resource);
            return resource;
        });
        TestBackend a = new TestBackend(), b = new TestBackend();
        ModelPipeLine<Object, Resource, String, String, String> pipeline = pipeline(adapter, a, b);
        pipeline.publishModels(Map.of("model", instance().getModel()));
        for (String id : List.of("one", "two")) {
            pipeline.createInstance("model", id, null, null, null);
            pipeline.addToRenderer("model", id, "bridge", "a");
            pipeline.addToRenderer("model", id, "bridge", "b");
        }
        assertThrows(IllegalStateException.class, () -> pipeline.publishModels(Map.of("model", instance().getModel())));
        assertTrue(a.getRenderingObjects().isEmpty());
        assertTrue(b.getRenderingObjects().isEmpty());
        assertFalse(pipeline.hasInstance("model", "one"));
        assertFalse(pipeline.hasInstance("model", "two"));
        assertEquals(4, resources.size());
        for (Resource resource : resources) assertEquals(1, resource.closes);
        a.close(); b.close();
    }

    private static ModelPipeLine<Object, Resource, String, String, String> pipeline(Adapter adapter, TestBackend a, TestBackend b) {
        adapter.allowLegacyRegistry = true;
        ModelLoader<Object, String, String> loader = new ModelLoader<>() {
            public Map<String, Model> load(String key, Object input) { throw new UnsupportedOperationException(); }
            public MaterialSetBuilder<String> materialSetBuilder() { throw new UnsupportedOperationException(); }
            public String getName() { return "test"; }
            public boolean isValidInput(Object input) { return false; }
            public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() { return new HashMap<>(); }
            public Texture loadTexture(Object key) { throw new UnsupportedOperationException(); }
        };
        return new ModelPipeLine.Builder<Object, Resource, String, String, String>()
                .withModelSource(new SourceManager<Object>(null, "test") {})
                .withLoader(loader).withBridge("bridge", adapter)
                .withBackend("a", a).withBackend("b", b).build();
    }

    private static final class Resource implements AutoCloseable {
        int closes;
        boolean fail;
        public void close() throws Exception {
            closes++;
            if (fail) throw new Exception("release");
        }
    }

    private static final class Context extends BackendContext<Adapter, Resource, Void, Void> {
        Context(Adapter adapter, ModelInstance instance) { super(adapter, instance, adapter.factory); }
        public Void beforeRender(Void ignored) { return null; }
    }

    private static final class TestBackend extends Backend<Adapter, Resource, Void, Void> {
        Context lastCreated;
        final List<ModelInstance> released = new ArrayList<>();
        @Override protected Context createContext(Adapter bridge, ModelInstance instance) {
            return lastCreated = new Context(bridge, instance);
        }
        @Override public void render(BackendContext<Adapter, Resource, Void, Void> context, Void ignored) {}
        @Override protected void onContextReleased(BackendContext<Adapter, Resource, Void, Void> context) {
            released.add(context.getModelInstance());
        }
    }

    private static final class Adapter implements Bridge<Resource> {
        RenderableFactory<Resource> factory;
        boolean allowLegacyRegistry;
        Adapter(RenderableFactory<Resource> factory) { this.factory = factory; }
        public HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton, Vertex[] vertices) { return new HashMap<>(); }
        public Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes) { return meshes; }
        public Resource getBackendRenderable(ModelInstance instance, HashMap<Vertex, Vertex> vertices, Mesh[] meshes) { return factory.createRenderable(instance); }
        public BoneBindingFunc getBoneBindingFunc(Model model, SkeletonInstance skeleton, Vertex vertex) { return vertex.getBinding().getFunc(); }
        public BackendContext<?, Resource, ?, ?> getBackendContext(ModelInstance instance) { throw new AssertionError("Backend must own context creation"); }
        public void setBackends(Map<String, Backend<?, Resource, ?, ?>> backends) {
            if (!allowLegacyRegistry) throw new AssertionError("No registry required");
        }
        public Map<String, Backend<?, Resource, ?, ?>> getBackends() { throw new AssertionError("No registry required"); }
    }

    private static ModelInstance instance() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], new Bone[]{root}, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, null, null);
    }
}
