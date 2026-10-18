package lib.kasuga.rendering.models.mc.typo;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.typo.gltf_entry.GltfModelManifest;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfAsset;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Minecraft resource-pack adapter for the common glTF/GLB loader. */
public final class KsgGltfLoader implements ModelLoader<byte[], ResourceLocation, Object> {
    private final String name;
    private final MaterialSetBuilder<Object> materialBuilder = new MaterialSetBuilder<>(this);
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources = new HashMap<>();
    private final Map<ResourceLocation, GltfModelManifest> manifests = new ConcurrentHashMap<>();

    public KsgGltfLoader(String name) { this.name = name; }

    @Override
    public Map<ResourceLocation, Model> load(ResourceLocation identifier, byte[] input) {
        try {
            GltfModelManifest manifest = GltfModelManifest.load(
                    Minecraft.getInstance().getResourceManager(), identifier);
            GltfAsset asset = GltfLoader.loadAllAnimations(new ByteArrayInputStream(input));
            Model model = GltfModelConverter.convert(asset, manifest.modelScale(), (imageIndex, texture) -> {
                String base = identifier.getPath().replaceAll("[^a-z0-9/._-]", "_");
                ResourceLocation textureLocation = ResourceLocation.fromNamespaceAndPath(identifier.getNamespace(),
                        "textures/gltf/" + Integer.toUnsignedString(base.hashCode()) + "/" + imageIndex);
                Pair<ResourceLocation, java.awt.image.BufferedImage> source = Pair.of(textureLocation, texture.image());
                Constants.TEXTURE_BASIC.load(source);
                MCTextureData data = new MCTextureData(source, Constants.TEXTURE_BASIC);
                return new MCTexture(texture.name(),
                        () -> new net.minecraft.client.resources.model.Material(
                                RenderState.KSG_LAYER_0, textureLocation),
                        texture.image().getWidth(), texture.image().getHeight(), data);
            });
            manifests.put(identifier, manifest);
            return Map.of(identifier, model);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to load glTF model " + identifier, exception);
        }
    }

    @Override public MaterialSetBuilder<Object> materialSetBuilder() { return materialBuilder; }
    @Override public String getName() { return name; }
    @Override public boolean isValidInput(Object input) { return input instanceof byte[]; }
    @Override public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() { return sidedSources; }
    @Override public Texture loadTexture(Object textureIdentifier) { return null; }

    public Optional<GltfModelManifest> manifest(ResourceLocation model) {
        return Optional.ofNullable(manifests.get(model));
    }
}
