package lib.kasuga.rendering.models.mc.java_and_bedrock.loader;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.IdentifierHelper;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.loaders.structural.Loader;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;

public class TextureLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        Loader loader = context.getLoader();
        HashMap<String, Pair<Material, MCTextureData>> textures = new HashMap<>();
        for (String key : input.keySet()) {
            String val = input.get(key).getAsString();
            Object identifier;
            ResourceLocation rl;
            Pair<ResourceLocation, Path> resolved = IdentifierHelper.getRLAndPath(val);
            Objects.requireNonNull(resolved);
            rl = resolved.getFirst();
            identifier = resolved.getSecond() == null ? rl : resolved.getSecond();
            Material material = new Material(RenderState.KSG_LAYER_0, rl);
            loader.loadType("texture", "mc_layer_0", identifier);
            KasugaTextureManager textureManager = (KasugaTextureManager)
                    ((HashMap<String, SourceManager>) loader.getSidedSources().get(Constants.TEXTURE_TYPE))
                    .get("mc_layer_0");
            textureManager.load(identifier);
            MCTextureData mcTextureData = new MCTextureData(
                    identifier,
                    textureManager
            );
            textures.put(key, Pair.of(material, mcTextureData));
        }
        context.setData("textures", textures);
    }
}
