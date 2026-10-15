package lib.kasuga.rendering.models.mc.api;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.source.model.assembly.AssemblyResourceDefinitions;
import lib.kasuga.rendering.models.uml.backend.*;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.*;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexPosMorph;
import lib.kasuga.rendering.models.uml.loaders.assembly.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.*;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class McModelAssembliesTest {
    private static final ResourceLocation BODY = id("body.bbmodel"), CLOTH = id("cloth.glb"), SUMMER = id("summer"), WINTER = id("winter");

    @Test
    void lazyRecipesResolveOnlyWhenAllSourcesPublishAndShareGeometryAcrossOwners() {
        try (Fixture f = new Fixture()) {
            f.service.register(WINTER, recipe(true));
            var first = f.service.handle(WINTER, id("owner-1"));
            assertFalse(first.mount()); assertEquals(0, f.service.cacheStats().builds());
            f.sources.publishModels(Map.of(BODY, model(1, 2)));
            assertFalse(first.mount()); assertEquals(0, f.service.cacheStats().builds());
            f.sources.publishModels(Map.of(CLOTH, model(1, 2)));
            assertTrue(first.mount());
            var second = f.service.handle(WINTER, id("owner-2")); assertTrue(second.mount());
            assertSame(first.instance().getModel(), second.instance().getModel());
            assertNotSame(first.instance(), second.instance());
            assertEquals(1, f.service.cacheStats().builds()); assertEquals(2, f.backend.getRenderingObjects().size());
            first.destroy(); second.destroy(); assertEquals(0, f.backend.getRenderingObjects().size());
        }
    }

    @Test
    void outfitSwitchIsPerOwnerAndPreservesExactRootAnimationMorphAndVisibility() {
        try (Fixture f = new Fixture()) {
            f.sources.publishModels(Map.of(BODY, model(1, 2)));
            f.service.register(SUMMER, recipe(false)); f.service.register(WINTER, recipe(true));
            var first = f.service.handle(SUMMER, id("owner-1")); var other = f.service.handle(SUMMER, id("owner-2"));
            assertTrue(first.mount()); assertTrue(other.mount());
            ModelInstance previous = first.instance(), unaffected = other.instance();
            previous.getSkeletonInstance().enableFloatingOrigin(new Vector3d(70_000_000.125, 20, -30));
            first.setPos(70_000_000.375, 21.5, -29.75).hide().maxRenderDistance(42).setAmbientLightEnhancement(2);
            var posing = previous.getPosing(); assertTrue(posing.play("walk", true));
            posing.setSpeed(1.5f); previous.animate(0.5f); previous.sample(1); posing.pause();
            previous.getMorph().activateMorph("fit", 0.75f); previous.getMorph().update();
            var clock = posing.timeline(); Vec3 position = first.getPos();
            assertFalse(first.switchAssembly(f.service, WINTER));
            assertSame(previous, first.instance()); assertTrue(f.backend.contains(previous));
            f.sources.publishModels(Map.of(CLOTH, model(1, 2)));
            assertTrue(first.switchAssembly(f.service, WINTER));
            ModelInstance fresh = first.instance();
            assertNotSame(previous, fresh); assertSame(unaffected, other.instance());
            assertTrue(f.backend.contains(unaffected)); assertFalse(f.backend.contains(previous));
            assertEquals(position, first.getPos()); assertEquals(42, ModelRenderScheduler.maxRenderDistance(fresh));
            assertFalse(first.shown()); assertEquals(2, fresh.getAmbientLightEnhancement());
            assertSame(posing, fresh.getPosing()); assertSame(clock, posing.timeline());
            assertEquals(0.75f, posing.currentTime(), 1e-6); assertFalse(posing.isPlaying());
            fresh.getMorph().update();
            assertEquals(0.75f, fresh.getMorph().getVertexResult(fresh.getModel().getVertices()[0]).getPosition().y, 1e-6);
            assertNull(previous.getPoseDriver());
            first.destroy(); other.destroy();
        }
    }

    @Test
    void publicationRetiresBorrowedAssembliesAndHandleRemountsWithFreshNamedClip() {
        try (Fixture f = new Fixture()) {
            f.sources.publishModels(Map.of(BODY, model(1, 2)));
            f.service.register(SUMMER, recipe(false));
            var handle = f.service.handle(SUMMER, id("owner")); assertTrue(handle.mount());
            var old = handle.instance(); var posing = old.getPosing(); posing.play("walk", true);
            old.animate(0.5f); old.sample(1); var clock = posing.timeline();
            old.getSkeletonInstance().enableFloatingOrigin(new Vector3d(50_000_000.25, 0, 0));
            handle.setPos(50_000_000.5, 0, 0);
            f.sources.publishModels(Map.of(BODY, model(4, 4)));
            assertFalse(handle.isMounted()); assertFalse(f.backend.contains(old));
            assertEquals(0, f.service.cacheStats().entries());
            assertEquals(new Vec3(50_000_000.5, 0, 0), handle.getPos());
            assertTrue(handle.mount()); var fresh = handle.instance(); fresh.sample(1);
            assertSame(clock, fresh.getPosing().timeline()); assertEquals(4, clock.snapshot().duration());
            assertEquals(2, fresh.getSkeletonInstance().getTransforms().get(fresh.getModel().getBones()[1]).getPosition().x, 1e-6);
            f.sources.replaceModels(Map.of());
            assertFalse(handle.isMounted()); assertFalse(handle.mount());
            handle.setPos(50_000_001.125, 1, 0);
            f.sources.publishModels(Map.of(BODY, model(1, 2)));
            assertTrue(handle.mount()); assertEquals(new Vec3(50_000_001.125, 1, 0), handle.getPos());
            handle.destroy();
        }
    }

    @Test
    void equivalentOutfitIdsHaveIndependentInstanceRegistries() {
        try (Fixture f = new Fixture()) {
            f.sources.publishModels(Map.of(BODY, model(1, 2)));
            f.service.register(SUMMER, recipe(false)); f.service.register(WINTER, recipe(false));
            var a = f.service.handle(SUMMER, id("same-owner"));
            var b = f.service.handle(WINTER, id("same-owner"));
            assertTrue(a.mount()); assertTrue(b.mount());
            assertSame(a.instance().getModel(), b.instance().getModel()); assertNotSame(a.instance(), b.instance());
            var before = b.instance(); f.service.unregister(SUMMER);
            assertFalse(a.isMounted()); assertTrue(b.isMounted()); assertSame(before, b.instance());
            assertTrue(f.backend.contains(before));
            a.destroy(); b.destroy();
        }
    }

    @Test
    void failedSetupOrBackendMountDoesNotLeakOrReplaceCurrentOutfit() {
        try (Fixture f = new Fixture()) {
            f.sources.publishModels(Map.of(BODY, model(1, 2), CLOTH, model(1, 2)));
            f.service.register(SUMMER, recipe(false)); f.service.register(WINTER, recipe(true));
            var handle = f.service.handle(SUMMER, id("owner")); assertTrue(handle.mount());
            var old = handle.instance();
            f.backend.fail = true;
            assertThrows(IllegalStateException.class, () -> handle.switchAssembly(f.service, WINTER));
            assertSame(old, handle.instance()); assertTrue(f.backend.contains(old));
            assertFalse(f.output.hasInstance(WINTER, id("owner")));
            f.backend.fail = false;
            handle.onInstanceChanged((previous, next) -> { throw new IllegalStateException("setup"); });
            assertThrows(IllegalStateException.class, () -> handle.switchAssembly(f.service, WINTER));
            assertSame(old, handle.instance()); assertEquals(1, f.backend.getRenderingObjects().size());
            handle.onInstanceChanged(null); assertTrue(handle.switchAssembly(f.service, WINTER));
            handle.destroy();
        }
    }

    @Test
    void resourceDefinitionsReloadRetainsProgrammaticOverridesAndClosesOldInstances() {
        try (Fixture f = new Fixture()) {
            f.sources.publishModels(Map.of(BODY, model(1, 2), CLOTH, model(1, 2)));
            f.service.register(SUMMER, recipe(false));
            f.service.replaceResourceDefinitions(Map.of(SUMMER, recipe(true), WINTER, recipe(true)));
            var h = f.service.handle(SUMMER, id("owner")); assertTrue(h.mount());
            assertEquals(1, h.instance().getModel().getMeshes().length);
            f.service.replaceResourceDefinitions(Map.of());
            assertFalse(h.isMounted()); assertTrue(h.mount());
            assertNull(f.service.resolve(WINTER)); assertEquals(1, h.instance().getModel().getMeshes().length);
            h.destroy();
            f.service.close(); assertThrows(IllegalStateException.class, () -> f.service.resolve(SUMMER));
        }
    }

    @Test
    void jsonRecipesAreFormatNeutralAndRejectInvalidIndicesAndFlags() {
        var definition = AssemblyResourceDefinitions.parse(JsonParser.parseString("""
                {"body":{"model":"example:models/body.mmd.zip","model_name":"character",
                 "regions":{"torso":[0,1]},"hide_regions":["torso"]},
                 "parts":[{"id":"shirt","model":"example:models/shirt.glb",
                 "bone_mappings":{"shirt_root":"root"},"include_dynamics":false}]}
                """).getAsJsonObject());
        assertEquals("character", definition.parts().getFirst().source().modelName());
        assertEquals(Set.of(0, 1), definition.parts().getFirst().hiddenMeshes());
        assertEquals(Map.of("shirt_root", "root"), definition.parts().get(1).boneMappings());
        assertFalse(definition.parts().get(1).includeDynamics());
        for (String invalid : List.of("{\"body\":{}}", "{\"body\":{\"model\":\"x:body\",\"hide_meshes\":[0.5]}}",
                "{\"body\":{\"model\":\"x:body\",\"include_dynamics\":\"false\"}}")) {
            assertThrows(RuntimeException.class, () -> AssemblyResourceDefinitions.parse(JsonParser.parseString(invalid).getAsJsonObject()));
        }
    }

    @Test
    void resourceBatchDropsRemovedAssetsAndKeepsModelsOwnedByOtherPublishers() {
        try (Fixture f = new Fixture()) {
            var external = id("external"); var own = model(1, 2);
            f.sources.publishModels(Map.of(external, own));
            var publisher = new ModelPublicationBatch<ResourceLocation>();
            publisher.publish(Map.of(f.sources, Map.of(BODY, model(1, 2))));
            f.service.register(SUMMER, recipe(false)); var h = f.service.handle(SUMMER, id("owner")); assertTrue(h.mount());
            publisher.publish(Map.of());
            assertFalse(h.isMounted()); assertNull(f.sources.getModel(BODY)); assertSame(own, f.sources.getModel(external));
            h.destroy();
        }
    }

    private static ModelAssemblyDefinition<McModelAssemblies.Reference> recipe(boolean cloth) {
        var builder = ModelAssemblyDefinition.builder("body", new McModelAssemblies.Reference(BODY));
        if (cloth) builder.part("shirt", new McModelAssemblies.Reference(CLOTH));
        return builder.build();
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test", path); }

    private static final class Fixture implements AutoCloseable {
        final TestBackend backend = new TestBackend();
        final Adapter bridge = new Adapter();
        final ModelPipeLine<Object, Object, ResourceLocation, ResourceLocation, Object> sources = pipeline(bridge, backend);
        final ModelPipeLine<Object, Object, ResourceLocation, ResourceLocation, Object> output = pipeline(bridge, backend);
        final McModelAssemblies service = new McModelAssemblies(output,
                ref -> new McModelAssemblies.PublishedSource(sources, ref.model()), new ModelAssemblyCache());
        public void close() {
            service.close(); sources.replaceModels(Map.of());
            try { backend.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
    private static ModelPipeLine<Object, Object, ResourceLocation, ResourceLocation, Object> pipeline(Adapter bridge, TestBackend backend) {
        return new ModelPipeLine.Builder<Object, Object, ResourceLocation, ResourceLocation, Object>()
                .withBridge("mc_bridge", bridge).withBackend("mc_backend", backend).buildForPublishedModels();
    }
    private static final class TestBackend extends Backend<Adapter, Object, Void, Void> {
        boolean fail;
        protected BackendContext<Adapter, Object, Void, Void> createContext(Adapter bridge, ModelInstance instance) {
            return new BackendContext<>(bridge, instance, ignored -> { if (fail) throw new IllegalStateException("mount"); return new Object(); }) {
                public Void beforeRender(Void ignored) { return null; }
            };
        }
        public void render(BackendContext<Adapter, Object, Void, Void> context, Void unused) {}
    }
    private static final class Adapter implements Bridge<Object> {
        public HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton, Vertex[] vertices) { return new HashMap<>(); }
        public Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes) { return meshes; }
        public Object getBackendRenderable(ModelInstance instance, HashMap<Vertex, Vertex> vertices, Mesh[] meshes) { return new Object(); }
        public BoneBindingFunc getBoneBindingFunc(Model model, SkeletonInstance skeleton, Vertex vertex) { return vertex.getBinding().getFunc(); }
        public BackendContext<?, Object, ?, ?> getBackendContext(ModelInstance instance) { throw new UnsupportedOperationException(); }
        public void setBackends(Map<String, Backend<?, Object, ?, ?>> backends) {}
        public Map<String, Backend<?, Object, ?, ?>> getBackends() { return Map.of(); }
    }
    private static Model model(int movement, float duration) {
        Bone root = new Bone("root", new Transform(), null), arm = new Bone("arm", new Transform().translate(0, 1, 0), null);
        root.setChildren(new Bone[]{arm}); arm.setParent(root);
        Skeleton skeleton = new Skeleton(new Bone[]{root, arm}, root, new Anchor[0], null, new Transform());
        Vertex[] vertices = new Vertex[3];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = new Vertex(new Vector3f(i, 1, 0), null);
            vertices[i].setBinding(new BoneBinding(new Pair[]{Pair.of(arm, 1f)}, BoneBindingFunc.BDEF, null));
        }
        Model model = new Model(vertices, new Mesh[]{new Mesh(vertices, new Vector3f(0, 0, 1), new Transform(), new Material[0], null)},
                skeleton.getBones(), skeleton, new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        model.getMorph().addMorph("fit", new VertexPosMorph<>(vertices[0], "fit", new Vector3f(vertices[0].getPosition()).add(0, 1, 0)));
        model.getAnimations().register("walk", new AnimationSampler<Integer>() {
            public float duration(Integer data) { return duration; }
            public Pose sample(Integer data, float seconds) { return Pose.bone("arm", new Transform().translate(seconds * data, 0, 0)); }
        }, movement);
        return model;
    }
}
