package lib.kasuga.rendering.models.mc.typo.mtb;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.text_stream.TextStreamLoader;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class KsgMtbLoader extends TextStreamLoader<ZipHelper, ResourceLocation, String, MtbContext> {

    private ZipHelper currentInput = null;

    private String currentModelName = null;

    public KsgMtbLoader(String name) {
        super(name, "\n", true);
        registerProcessor("metadata", new MtbMetaProcessor());
        registerProcessor("element", new MtbElementProcessor());
    }

    @Override
    public void beforeProcessors(SerialContext<MtbContext> context) {
        context.push(new MtbContext());
    }

    @Override
    public String getAsString(ZipHelper input) {
        ZipResource resource = input.getResource("Model.txt");
        if (resource != null) {
            currentInput = input;
            return resource.buffer().asCharBuffer().toString();
        }
        return "";
    }

    @Override
    public void build(HashMap<ResourceLocation, Model> result) {
        MtbContext context = getContext().pop();
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ZipHelper;
    }

    @Override
    public Texture loadTexture(Object textureIdentifier) {
        Objects.requireNonNull(currentInput);
        Objects.requireNonNull(currentModelName);
        CombinedTextureManager manager = Constants.TEXTURE_BASIC;
        ZipResource textureResource = currentInput.getResource("Model.png");
        if (textureResource == null) {
            ResourceLocation rl = MissingTextureAtlasSprite.getLocation();
            MCTextureData data = new MCTextureData(rl, manager);
            return new MCTexture("main",
                    () -> new Material(RenderState.KSG_LAYER_0, rl),
                    16, 16, data);
        }
        ResourceLocation rl = ResourceLocation.tryBuild("kasuga_lib",
                "textures/mtb/" + currentModelName.toLowerCase() + "model.png");
        Objects.requireNonNull(rl);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(textureResource.buffer().array())) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image != null) {
                Pair<ResourceLocation, BufferedImage> pair = Pair.of(rl, image);
                MCTextureData data = new MCTextureData(pair, manager);
                MCTexture texture = new MCTexture("main",
                        () -> new Material(RenderState.KSG_LAYER_0, rl),
                        image.getWidth(), image.getHeight(), data);
                manager.load(pair);
                return texture;
            } else {
                throw new RuntimeException("Failed to read PNG texture from MTB file");
            }
        } catch (Exception e) {
            throw new RuntimeException("PNG texture file is required", e);
        }
    }
}
