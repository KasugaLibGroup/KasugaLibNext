package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.IdentifierHelper;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEModelData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.SpriteSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.loaders.structural.Loader;
import lib.kasuga.structure.Pair;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class JETextureLayer extends Layer<JEModelData> {

    @Override
    public void process(JEModelData modelData, Context context) {
        Map<String, String> rawTextures = modelData.getTextures();
        if (rawTextures.isEmpty()) {
            return;
        }

        Loader loader = context.getLoader();
        HashMap<String, Pair<Material, MCTextureData>> textures = new HashMap<>();
        Set<Object> loadedTextureIds = new HashSet<>();

        KasugaTextureManager textureManager = (KasugaTextureManager)
                ((HashMap<String, SourceManager>) loader.getSidedSources().get(Constants.TEXTURE_TYPE))
                .get("mc_layer_0");

        for (Map.Entry<String, String> entry : rawTextures.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String resolvedValue = resolveTextureRef(value, rawTextures);

            Pair<ResourceLocation, Path> resolved = IdentifierHelper.getRLAndPath(resolvedValue);
            Objects.requireNonNull(resolved, "Could not resolve texture: " + resolvedValue);

            ResourceLocation rl = resolved.getFirst();
            Object identifier = resolved.getSecond() == null ? rl : resolved.getSecond();

            Material material = new Material(RenderState.KSG_LAYER_0, rl);

            if (!loadedTextureIds.contains(identifier)) {
                loadedTextureIds.add(identifier);
                loader.loadType("texture", "mc_layer_0", identifier);
                textureManager.load(identifier);
            }

            MCTextureData mcTextureData = new MCTextureData(identifier, textureManager);
            textures.put(key, Pair.of(material, mcTextureData));
        }

        context.setData("textures", textures);

        MaterialSetBuilder<String> builder = context.getLoader().getMaterialSetBuilder();

        for (Map.Entry<String, Pair<Material, MCTextureData>> entry : textures.entrySet()) {
            String id = entry.getKey();
            Pair<Material, MCTextureData> pair = entry.getValue();
            MCTexture texture = new MCTexture(
                    id, pair::getFirst,
                    16, 16,
                    pair.getSecond());
            builder.registerTexture(id, texture);
        }

        String firstTextureId = textures.keySet().iterator().next();
        builder.beginMaterial()
                .useTexture(firstTextureId)
                .addSpriteBuildingFunc((mtlb, sprb, mtl) -> {
                    SpriteSetBuilder<String> sprBuilder = sprb;
                    sprBuilder.textureId(firstTextureId)
                            .rectangularUVs(new Vector2f(), new Vector2f(1f, 1f))
                            .endSprite();
                })
                .endMaterial();

        context.setData("material", builder.getMaterials().getFirst());
    }

    private String resolveTextureRef(String value, Map<String, String> textures) {
        if (value.startsWith("#")) {
            String refKey = value.substring(1);
            String resolved = textures.get(refKey);
            if (resolved == null) {
                throw new IllegalStateException("Texture reference '#" + refKey + "' could not be resolved in textures: " + textures);
            }
            return resolveTextureRef(resolved, textures);
        }
        return value;
    }
}
