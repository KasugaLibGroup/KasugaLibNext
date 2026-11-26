package test.kasuga.modelling.oit;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.backend.schedule.RenderScheduleMode;
import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.SpriteHolder;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.ModelPublicationBatch;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Programmatic, main-world fixture that enters the production MCBackend BLEND/OIT scheduler.
 * Used by the packaged client smoke test and the development world-model command.
 */
public final class OitWorldBlendFixture {
    private static final ResourceLocation MODEL_ID = ResourceLocation.fromNamespaceAndPath("kasuga_oit_smoke", "overlap");
    private static final ResourceLocation INSTANCE_ID = ResourceLocation.fromNamespaceAndPath("kasuga_oit_smoke", "world_fixture");
    private static final float ALPHA = 0.42f;
    private static ModelPipeLine<ResourceLocation, ResourceLocation> pipeline;
    private static ModelPublicationBatch<ResourceLocation> publication;
    private static ModelInstance instance;

    private OitWorldBlendFixture() {}

    /** Create and register the overlapping three-layer BLEND model in the current client world, hidden. */
    public static void install() {
        RenderSystem.assertOnRenderThread();
        close();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null)
            throw new IllegalStateException("OIT world fixture requires an active singleplayer world and player");
        if (PipelineRegistry.backend() == null)
            throw new IllegalStateException("Kasuga MCBackend is not initialized yet");
        if (Constants.TEXTURE_BASIC == null)
            throw new IllegalStateException("Kasuga model texture atlas is not initialized yet");

        pipeline = new ModelPipeLine.PublishedBuilder<ResourceLocation, ResourceLocation, BackendInstance>()
                .withBridge("mc_bridge", new MCBridge())
                .withBackend("mc_backend", PipelineRegistry.backend())
                .build();
        publication = new ModelPublicationBatch<>();
        try {
            publication.put(pipeline, Map.of(MODEL_ID, createModel()));
            instance = pipeline.createInstance(MODEL_ID, INSTANCE_ID, new Transform(), null, null);
            if (instance == null) throw new IllegalStateException("Failed to create OIT world fixture instance");
            pipeline.addToRenderer(MODEL_ID, INSTANCE_ID, "mc_bridge", "mc_backend");
            Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            instance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(
                    cameraPosition.x, cameraPosition.y, cameraPosition.z));
            placeInView();
            ModelRenderScheduler.setMode(instance,
                    RenderScheduleMode.MANUAL);
            ModelRenderScheduler.setVisible(instance, false);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** Toggle only model visibility; call from the render/client thread. */
    public static void show(boolean show) {
        RenderSystem.assertOnRenderThread();
        requireInstalled();
        placeInView();
        ModelRenderScheduler.setVisible(instance, show);
    }

    public static boolean isInstalled() {
        return pipeline != null && instance != null && !instance.isClosed();
    }

    /** Remove the instance and its published model. Safe to call repeatedly. */
    public static void close() {
        RenderSystem.assertOnRenderThread();
        if (pipeline != null && instance != null) {
            pipeline.removeInstance(MODEL_ID, INSTANCE_ID);
        }
        if (publication != null && pipeline != null) publication.remove(pipeline, MODEL_ID);
        if (publication != null) publication.close();
        pipeline = null;
        publication = null;
        instance = null;
    }

    private static void placeInView() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f forward = new Vector3f(camera.getLookVector()).normalize();
        Vector3f center = camera.getPosition().toVector3f().fma(3.25f, forward);
        Quaternionf facing = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(forward).negate());
        Transform transform = new Transform().mul(facing).setPosition(center);
        instance.getSkeletonInstance().rebaseFloatingOrigin(new Vector3d(
                camera.getPosition().x, camera.getPosition().y, camera.getPosition().z));
        instance.getSkeletonInstance().transformRootWorld(transform);
        instance.updateImmediate();
    }

    private static Model createModel() {
        Bone root = new Bone("oit_smoke_root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        TextureAtlasSprite sprite = Constants.TEXTURE_BASIC.getTextureAtlas()
                .getSprite(MissingTextureAtlasSprite.getLocation());
        int sourcePixel = sprite.contents().getOriginalImage().getPixelRGBA(4, 12);
        int sourceAlpha = sourcePixel >>> 24 & 255;
        if (sourceAlpha < 200)
            throw new IllegalStateException("Kasuga missing-texture atlas texel is not opaque; RGBA alpha=" + sourceAlpha);
        TextureData textureData = new AtlasTexture(sprite);
        List<Texture> textures = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        List<Vertex> allVertices = new ArrayList<>();
        float[][] colors = {{1f, 0.12f, 0.1f}, {0.1f, 1f, 0.2f}, {0.1f, 0.3f, 1f}};
        float[] offsets = {-0.12f, 0f, 0.12f};
        for (int layer = 0; layer < colors.length; layer++) {
            Texture texture = new Texture("oit_smoke_" + layer, 16, 16, textureData);
            Material material = new Material(new Texture[]{texture}, new MaterialData() {
                @Override public MaterialAlphaMode alphaMode() { return MaterialAlphaMode.BLEND; }
            });
            material.addSprite(new SpriteSet(null, new Sprite(texture, new Vector2f(), new Vector2f(1, 0),
                    new Vector2f(1, 1), new Vector2f(0, 1),
                    new Vector4f(colors[layer][0], colors[layer][1], colors[layer][2], ALPHA), null, null, null)));
            Vertex[] vertices = {
                    new Vertex(new Vector3f(-0.82f, -0.82f, offsets[layer]), null),
                    new Vertex(new Vector3f(0.82f, -0.82f, offsets[layer]), null),
                    new Vertex(new Vector3f(0.82f, 0.82f, offsets[layer]), null),
                    new Vertex(new Vector3f(-0.82f, 0.82f, offsets[layer]), null)
            };
            Mesh mesh = new Mesh(vertices, new Vector3f(0, 0, 1), new Transform(), new Material[]{material}, null);
            for (int index = 0; index < vertices.length; index++) {
                Vertex vertex = vertices[index];
                vertex.setBinding(new BoneBinding(new Pair[]{Pair.of(root, 1f)}, BoneBindingFunc.BDEF, null));
                vertex.addUV(mesh, material, new Vector2f(index == 0 || index == 3 ? 0.2f : 0.3f,
                        index < 2 ? 0.7f : 0.8f));
                allVertices.add(vertex);
            }
            textures.add(texture);
            materials.add(material);
            meshes.add(mesh);
        }
        return new Model(allVertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new),
                new Bone[]{root}, skeleton, new MaterialSet(textures, materials), MeshMode.QUADS, null, null);
    }

    private static void requireInstalled() {
        if (!isInstalled()) throw new IllegalStateException("Call OitWorldBlendFixture.install() in an active world first");
    }

    private record AtlasTexture(TextureAtlasSprite sprite) implements TextureData, SpriteHolder {
        @Override public TextureAtlasSprite getSprite() { return sprite; }
        @Override public boolean shouldDividedByTextureSize() { return false; }
    }
}
