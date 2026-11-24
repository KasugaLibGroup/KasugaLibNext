package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.source.model.KasugaPipeLineRouter;
import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.ModelPublicationBatch;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelLightingDefaultsTest {
    @Test
    void nativePipelineDefaultsApplyToNewWorldAndItemOwnersAndExplicitOverridesSurviveReload() throws Exception {
        ResourceLocation model = ResourceLocation.parse("lighting_test:opt_in.bbmodel");
        ResourceLocation unrelated = ResourceLocation.parse("lighting_test:unconfigured.bbmodel");
        ModelLightingDefaults.register(model, ModelLightingMode.BLOCK);
        var routerField = PipelineRegistry.class.getDeclaredField("router");
        routerField.setAccessible(true);
        Object previousRouter = routerField.get(null);
        try (MCBackend backend = new MCBackend(); ModelPublicationBatch<ResourceLocation> publication = new ModelPublicationBatch<>()) {
            ModelPipeLine<ResourceLocation, ResourceLocation> pipeline =
                    new ModelPipeLine.PublishedBuilder<ResourceLocation, ResourceLocation, BackendInstance>()
                            .withBridge("mc_bridge", new MCBridge()).withBackend("mc_backend", backend).build();
            KasugaPipeLineRouter router = new KasugaPipeLineRouter();
            router.registerByExtension(".bbmodel", () -> pipeline);
            PipelineRegistry.registerDefaultRoutes(router);
            publication.put(pipeline, Map.of(model, emptyModel(), unrelated, emptyModel()));
            McModelHandle world = McModelHandle.of(model, null, ResourceLocation.parse("lighting_test:world"), null);
            McModelHandle item = McModelHandle.of(model, null, ResourceLocation.parse("lighting_test:item"), null);
            McModelHandle other = McModelHandle.of(unrelated, null, ResourceLocation.parse("lighting_test:other"), null);
            try {
                assertTrue(world.mount()); assertTrue(item.mount()); assertTrue(other.mount());
                assertEquals(ModelLightingMode.BLOCK, world.instance().getLightingMode());
                assertEquals(ModelLightingMode.BLOCK, item.instance().getLightingMode());
                assertEquals(ModelLightingMode.PBR, other.instance().getLightingMode());
                world.setLightingMode(ModelLightingMode.PBR);
                assertTrue(world.mount());
                assertEquals(ModelLightingMode.PBR, world.instance().getLightingMode());
                publication.put(pipeline, Map.of(model, emptyModel(), unrelated, emptyModel()));
                assertTrue(world.mount()); assertTrue(item.mount());
                assertEquals(ModelLightingMode.PBR, world.instance().getLightingMode());
                assertEquals(ModelLightingMode.BLOCK, item.instance().getLightingMode());
            } finally {
                world.destroy(); item.destroy(); other.destroy();
            }
        } finally {
            routerField.set(null, previousRouter);
        }
    }

    private static Model emptyModel() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        return new Model(new Vertex[0], new Mesh[0], new Bone[]{root}, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
    }
}
