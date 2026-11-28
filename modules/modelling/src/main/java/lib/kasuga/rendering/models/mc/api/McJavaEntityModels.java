package lib.kasuga.rendering.models.mc.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.entity.JavaEntityModelLoader;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.joml.Vector2f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client-side registration and pose API for Java models addressed by virtual .java IDs.
 * A resource pack can publish {@code example:display/pig.java} by adding
 * {@code assets/example/java_entity_models/display/pig.json} with
 * {@code {"model":"minecraft:pig", "layer":"main", "texture":"minecraft:entity/pig/pig"}}.
 * The layer name defaults to {@code main}. The texture ID is relative to {@code textures/}.
 */
public final class McJavaEntityModels {
    public static final ResourceLocation CREEPER = vanillaId("creeper");
    public static final ResourceLocation PLAYER = vanillaId("player");
    public static final ResourceLocation PLAYER_SLIM = vanillaId("player_slim");

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter DEFINITIONS_PATH = FileToIdConverter.json("java_entity_models");
    private static final Map<ResourceLocation, Definition> DEFINITIONS = new LinkedHashMap<>();
    private static final MaterialData CUTOUT = new MaterialData() {
        @Override public MaterialAlphaMode alphaMode() { return MaterialAlphaMode.MASK; }
        @Override public float alphaCutoff() { return 0.1f; }
    };

    static {
        register(CREEPER, ModelLayers.CREEPER, vanillaTexture("creeper/creeper"));
        register(PLAYER, layers -> bakePlayer(layers, ModelLayers.PLAYER), vanillaTexture("player/wide/steve"), 0, 0);
        register(PLAYER_SLIM, layers -> bakePlayer(layers, ModelLayers.PLAYER_SLIM), vanillaTexture("player/slim/alex"), 0, 0);
        register(vanillaId("zombie"), ModelLayers.ZOMBIE, vanillaTexture("zombie/zombie"));
        register(vanillaId("skeleton"), ModelLayers.SKELETON, vanillaTexture("skeleton/skeleton"));
        register(vanillaId("pig"), ModelLayers.PIG, vanillaTexture("pig/pig"));
        register(vanillaId("cow"), ModelLayers.COW, vanillaTexture("cow/cow"));
        register(vanillaId("sheep"), ModelLayers.SHEEP, vanillaTexture("sheep/sheep"));
        register(vanillaId("chicken"), ModelLayers.CHICKEN, vanillaTexture("chicken"));
        register(vanillaId("spider"), ModelLayers.SPIDER, vanillaTexture("spider/spider"));
    }

    private McJavaEntityModels() {}

    /** Registers an existing Java model layer for the next client resource reload. */
    public static void register(ResourceLocation id, ModelLayerLocation layer, ResourceLocation texture) {
        Objects.requireNonNull(layer, "layer");
        register(id, layers -> layers.bakeLayer(layer), texture, 0, 0);
    }

    /**
     * Registers a custom model for the next client resource reload. The supplier runs on the
     * client thread and must return a freshly baked tree. The texture ID is relative to
     * {@code textures/} and omits the {@code .png} suffix.
     */
    public static void register(ResourceLocation id, Supplier<ModelPart> root,
                                ResourceLocation texture, int width, int height) {
        Objects.requireNonNull(root, "root");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Expected positive texture dimensions for " + id);
        }
        register(id, layers -> root.get(), texture, width, height);
    }

    private static synchronized void register(ResourceLocation id, Function<EntityModelSet, ModelPart> root,
                                              ResourceLocation texture, int width, int height) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(texture, "texture");
        if (!id.getPath().endsWith(".java")) {
            throw new IllegalArgumentException("Expected a .java model ID: " + id);
        }
        if (DEFINITIONS.putIfAbsent(id, new Definition(root, texture, width, height)) != null) {
            throw new IllegalArgumentException("Duplicate Java model ID: " + id);
        }
    }

    /** Copies transforms from a matching posed tree. Visibility is set when the model is baked. */
    public static void applyPose(ModelInstance instance, ModelPart posedRoot) {
        JavaEntityModelLoader.applyPose(instance, posedRoot);
    }

    /** Reads layer references and queues their textures before the shared atlas is stitched. */
    public static Prepared prepare(ResourceManager resources, KasugaTextureManager textures) {
        Map<ResourceLocation, Definition> definitions;
        synchronized (McJavaEntityModels.class) {
            definitions = new LinkedHashMap<>(DEFINITIONS);
        }
        for (Map.Entry<ResourceLocation, Resource> entry : DEFINITIONS_PATH.listMatchingResources(resources).entrySet()) {
            ResourceLocation file = entry.getKey();
            ResourceLocation id = DEFINITIONS_PATH.fileToId(file).withSuffix(".java");
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                ResourceLocation model = ResourceLocation.parse(GsonHelper.getAsString(json, "model"));
                String layer = GsonHelper.getAsString(json, "layer", "main");
                ResourceLocation texture = ResourceLocation.parse(GsonHelper.getAsString(json, "texture"));
                ModelLayerLocation location = new ModelLayerLocation(model, layer);
                definitions.put(id, new Definition(layers -> layers.bakeLayer(location), texture, 0, 0));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Invalid Java entity model definition {}", file, failure);
            }
        }
        for (Definition definition : definitions.values()) {
            textures.load(definition.texture());
        }
        return new Prepared(definitions, textures);
    }

    public static final class Prepared {
        private final Map<ResourceLocation, Definition> definitions;
        private final KasugaTextureManager textures;

        private Prepared(Map<ResourceLocation, Definition> definitions, KasugaTextureManager textures) {
            this.definitions = definitions;
            this.textures = textures;
        }

        /** Converts the references after the vanilla layer registry and shared atlas have reloaded. */
        public Map<ResourceLocation, Model> bake(EntityModelSet layers) {
            Map<ResourceLocation, Model> models = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, Definition> entry : definitions.entrySet()) {
                ResourceLocation id = entry.getKey();
                Definition definition = entry.getValue();
                try {
                    TextureAtlasSprite sprite = textures.get(definition.texture());
                    if (sprite == null) {
                        LOGGER.warn("Missing texture {} for Java entity model {}", definition.texture(), id);
                        continue;
                    }
                    ModelPart root = definition.root().apply(layers);
                    MaterialSet materials = createMaterial(definition, textures, sprite);
                    models.put(id, JavaEntityModelLoader.bake(root, materials, materials.getMaterials()[0]));
                } catch (RuntimeException failure) {
                    LOGGER.warn("Could not bake Java entity model {}", id, failure);
                }
            }
            return models;
        }
    }

    private static MaterialSet createMaterial(Definition definition, KasugaTextureManager textures, TextureAtlasSprite sprite) {
        ResourceLocation texture = definition.texture();
        int width = definition.width() > 0 ? definition.width() : sprite.contents().width();
        int height = definition.height() > 0 ? definition.height() : sprite.contents().height();
        net.minecraft.client.resources.model.Material atlasMaterial =
                new net.minecraft.client.resources.model.Material(textures.getTextureAtlas().location(), texture);
        MCTexture modelTexture = new MCTexture(texture.toString(), () -> atlasMaterial, width, height,
                new MCTextureData(texture, textures, true));
        Material material = new Material(new Texture[]{modelTexture}, CUTOUT);
        material.hookTextures();
        Sprite fullTexture = new Sprite(modelTexture, new Vector2f(0, 0), new Vector2f(1, 0),
                new Vector2f(1, 1), new Vector2f(0, 1), null, null, null, null);
        material.addSprite(new SpriteSet(null, fullTexture));
        return new MaterialSet(modelTexture, material);
    }

    private static ModelPart bakePlayer(EntityModelSet layers, ModelLayerLocation layer) {
        ModelPart root = layers.bakeLayer(layer);
        // The native player renders these separately from the skin.
        root.getChild("cloak").skipDraw = true;
        root.getChild("ear").skipDraw = true;
        return root;
    }

    private static ResourceLocation vanillaId(String name) {
        return ResourceLocation.withDefaultNamespace("vanilla/" + name + ".java");
    }

    private static ResourceLocation vanillaTexture(String path) {
        return ResourceLocation.withDefaultNamespace("entity/" + path);
    }

    private record Definition(Function<EntityModelSet, ModelPart> root, ResourceLocation texture, int width, int height) {}
}
