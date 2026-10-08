package lib.kasuga.rendering.models.mc.typo;

import com.mojang.logging.LogUtils;
import com.google.common.hash.Hashing;
import lib.kasuga.client.loading.LoadingIndicator;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.api.pbr.PbrConversionRegistry;
import lib.kasuga.rendering.models.mc.api.pbr.PbrConversionSettings;
import lib.kasuga.rendering.models.mc.api.pbr.PbrMaterialContext;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeProfile;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.KsgPmxContext;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipMeta;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.SpriteSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterialData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.pmd.PmdToPmxConverter;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class KsgPmxLoader extends PMXLoader<ZipHelper, ResourceLocation, ZipResource, KsgPmxContext> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ZipHelper loadingFile;

    private ZipResource loadingModel;
    private ResourceLocation loadingModelLocation;
    private int loadingMaterialIndex;

    private final List<Pair<ZipResource, Texture>> loadedTextures;

    private final HashMap<ZipResource, Texture> loadedTextureMap;
    private final Map<PbrTextureVariantKey, Texture> loadedPbrTextureVariants;

    public final MCTexture MISSING, MISSING_TRANSPARENCY;

    private final Map<ResourceLocation, Map<String, ResourceLocation>> loadedModelMap;

    public KsgPmxLoader(String name) {
        super(name);
        loadingFile = null;
        loadingModel = null;
        loadingModelLocation = null;
        loadingMaterialIndex = 0;
        loadedTextures = new ArrayList<>();
        loadedTextureMap = new HashMap<>();
        loadedPbrTextureVariants = new HashMap<>();
        MISSING = new MCTexture("missingno",
                () -> new net.minecraft.client.resources.model.Material(
                        RenderState.KSG_LAYER_0, MissingTextureAtlasSprite.getLocation()
                ), 16, 16,
                new MCTextureData(
                        MissingTextureAtlasSprite.getLocation(),
                        Constants.TEXTURE_BASIC
                ));
        MISSING_TRANSPARENCY = new MCTexture("transparencyno",
                () -> new net.minecraft.client.resources.model.Material(
                        RenderState.KSG_LAYER_0, RenderState.DEFAULT_TRANSPARENCY
                ), 16, 16,
                new MCTextureData(
                        RenderState.DEFAULT_TRANSPARENCY,
                        Constants.TEXTURE_BASIC
                ));
        loadedModelMap = new HashMap<>();
    }

    @Override
    public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {
        boolean useInternalToon = material.usingInternalTexture;
        int index = material.textureIndex.intValue();
        int materialIndex = loadingMaterialIndex++;
        Object identifier = getDefaultTextureIdentifier(index, loadedTextures);
        BufferedImage sourceImage = null;
        if (index >= 0 && index < loadedTextures.size()) {
            ZipResource sourceResource = loadedTextures.get(index).getFirst();
            Texture texture = loadedTextures.get(index).getSecond();
            if (texture.getData() instanceof MCTextureData data
                    && data.getIdentifier() instanceof Pair<?, ?> source
                && source.getFirst() instanceof ResourceLocation textureId
                && source.getSecond() instanceof BufferedImage image) {
                sourceImage = image;
                PbrBakeProfile automatic = PbrBakeProfile.from(material);
                PbrMaterialContext conversionContext = new PbrMaterialContext(
                        Objects.requireNonNull(loadingModelLocation), textureId, materialIndex,
                        material.localTextureName, material.engTextureName, material.metaData,
                        material.diffuseColor.x, material.diffuseColor.y, material.diffuseColor.z, material.diffuseColor.w,
                        material.specularColor.x, material.specularColor.y, material.specularColor.z,
                        material.ambientColor.x, material.ambientColor.y, material.ambientColor.z,
                        material.shininess, material.flags.noCull, material.flags.receiveShadow,
                        image.getWidth(), image.getHeight()
                );
                PbrConversionSettings settings = PbrConversionRegistry.apply(
                        conversionContext, automatic.toSettings()
                );
                PbrBakeProfile profile = PbrBakeProfile.from(settings);
                PbrTextureVariantKey variantKey = new PbrTextureVariantKey(sourceResource, profile);
                Texture variantTexture = loadedPbrTextureVariants.computeIfAbsent(
                        variantKey, ignored -> createPbrTextureVariant(texture, textureId, image, profile)
                );
                builder.registerTexture(variantKey, variantTexture);
                Constants.TEXTURE_BASIC.requestPbrBake(
                        ((MCTextureData) variantTexture.getData()).getIdentifier(), image, profile
                );
                identifier = variantKey;
            }
        }
        builder.setMaterialData(PmxMaterialData.from(material, sourceImage));
        if (identifier == null) {
            identifier = loadedTextures.get(index).getFirst();
        }
        builder.useTexture(identifier);
        final Object spriteTextureIdentifier = identifier;
        builder.addSpriteBuildingFunc((matb, sprb, mat) -> {
            SpriteSetBuilder spriteBuilder = (SpriteSetBuilder) sprb;
            spriteBuilder
                    .textureId(spriteTextureIdentifier)
                    .culled(!material.flags.noCull)
                    .shade(material.flags.drawShadow)
                    .color(new Vector4f(material.diffuseColor))
                    .endSprite();
        }).endMaterial(material);
    }

    private Texture createPbrTextureVariant(Texture sourceTexture, ResourceLocation sourceLocation,
                                            BufferedImage image, PbrBakeProfile profile) {
        ResourceLocation variantLocation = profile.variantLocation(sourceLocation);
        Pair<ResourceLocation, BufferedImage> variantIdentifier = Pair.of(variantLocation, image);
        CombinedTextureManager textureManager = Constants.TEXTURE_BASIC;
        net.minecraft.client.resources.model.Material atlasMaterial =
                new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, variantLocation);
        MCTexture variant = new MCTexture(
                sourceTexture.getId() + "#" + variantLocation.getPath(),
                () -> atlasMaterial,
                sourceTexture.getWidth(), sourceTexture.getHeight(),
                new MCTextureData(variantIdentifier, textureManager)
        );
        textureManager.load(variantIdentifier);
        return variant;
    }

    public ResourceLocation getDefaultTextureIdentifier(int index, List<?> list) {
        if (index < 0) return RenderState.DEFAULT_TRANSPARENCY;
        if (index >= list.size()) return MissingTextureAtlasSprite.getLocation();
        Object pair = list.get(index);
        if (!(pair instanceof Pair<?,?> p)) return MissingTextureAtlasSprite.getLocation();
        return p.getFirst() == null ? MissingTextureAtlasSprite.getLocation() : null;
    }

    @Override
    public ZipResource getTextureIdentifier(String texturePath) {
        Objects.requireNonNull(loadingFile);
        Objects.requireNonNull(loadingModel);
        String normalized = ZipHelper.normalizeEntryName(texturePath);
        int slash = loadingModel.name().lastIndexOf('/');
        ZipResource relative = slash < 0 ? null : loadingFile.getResource(
                loadingModel.name().substring(0, slash + 1) + normalized
        );
        if (relative != null) return relative;
        ZipResource direct = loadingFile.getResource(normalized);
        if (direct == null) {
            LOGGER.warn("PMX texture '{}' (normalized to '{}') was not found in {}",
                    texturePath, normalized, loadingFile.getPath());
        }
        return direct;
    }

    public Texture getTexture(ZipResource s) {
        try {
            // Include the archive: separate outfits often contain the same model.pmx and texture names.
            String modelKey = loadingModel == null ? "?" : loadingModel.name();
            ResourceLocation rl = textureLocation(s.file().getPath(), modelKey, s.name());
            if (loadedTextureMap.containsKey(s)) {
                Texture texture = loadedTextureMap.get(s);
                loadedTextures.add(Pair.of(s, texture));
                return texture;
            }
            ByteArrayInputStream bis = new ByteArrayInputStream(copyBytes(s.buffer()));
            BufferedImage image = ImageIO.read(bis);
            bis.close();
            if (image == null) {
                if (!s.name().toLowerCase(Locale.ROOT).endsWith(".tga")) {
                    throw new RuntimeException("Unsupported image format: " + s.name());
                }
                ByteBuffer bb = null;
                ByteBuffer copied = MemoryUtil.memAlloc(s.buffer().remaining());
                copied.order(ByteOrder.nativeOrder());
                copied.put(s.buffer().duplicate());
                copied.flip();

                IntBuffer w = MemoryUtil.memAllocInt(1);
                IntBuffer h = MemoryUtil.memAllocInt(1);
                IntBuffer comp = MemoryUtil.memAllocInt(1);

                try {
                    bb = STBImage.stbi_load_from_memory(copied, w, h, comp, 4);
                    if (bb == null) {
                        throw new RuntimeException("Failed to load TGA image: " + STBImage.stbi_failure_reason());
                    }
                    image = new BufferedImage(w.get(0), h.get(0), BufferedImage.TYPE_4BYTE_ABGR);
                    byte[] imageData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                    for (int i = 0; i < bb.remaining(); i += 4) {
                        // STBImage loads in RGBA, but BufferedImage expects ABGR, so we need to swap the R and B channels
                        byte r = bb.get(i);
                        byte g = bb.get(i + 1);
                        byte b = bb.get(i + 2);
                        byte a = bb.get(i + 3);

                        imageData[i] = a;
                        imageData[i + 1] = b;
                        imageData[i + 2] = g;
                        imageData[i + 3] = r;
                    }
                } finally {
                    MemoryUtil.memFree(copied);
                    MemoryUtil.memFree(w);
                    MemoryUtil.memFree(h);
                    MemoryUtil.memFree(comp);
                    if (bb != null) {
                        STBImage.stbi_image_free(bb);
                    }
                }
            }
            if (image.getType() != BufferedImage.TYPE_4BYTE_ABGR) {
                BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = converted.createGraphics();
                try {
                    g.setComposite(AlphaComposite.Src);
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
                image = converted;
            }
            CombinedTextureManager textureManager = Constants.TEXTURE_BASIC;
            int w = image.getWidth(), h = image.getHeight();
            net.minecraft.client.resources.model.Material mat = new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, rl);
            Pair<ResourceLocation, BufferedImage> pair = Pair.of(rl, image);
            MCTextureData data = new MCTextureData(pair, textureManager);
            MCTexture mcTexture = new MCTexture(s.name(), () -> mat, w, h, data);
            loadedTextures.add(Pair.of(s, mcTexture));
            loadedTextureMap.put(s, mcTexture);
            return mcTexture;
        } catch (Exception e) {
            LOGGER.warn("Failed to decode PMX texture '{}'; using the missing texture", s.name(), e);
            loadedTextures.add(Pair.of(s, MISSING));
            loadedTextureMap.put(s, MISSING);
            return MISSING;
        }
    }

    static ResourceLocation textureLocation(Object archive, String modelEntry, String textureEntry) {
        String key = Objects.requireNonNull(archive) + "\0" + Objects.requireNonNull(modelEntry) + "\0"
                + Objects.requireNonNull(textureEntry);
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", "textures/pmx/"
                + Hashing.sha256().hashString(key, StandardCharsets.UTF_8));
    }

    @Override
    public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertex) {
        if (vertex.isEmpty() || first == null) {
            throw new IllegalArgumentException("Vertex collection cannot be empty");
        }
        if (first.binding.type == PmxBoneBinding.BindingType.SDEF && first.binding.data != null) {
            first.binding.data.c().mul(activeModelScale());
            first.binding.data.r0().mul(activeModelScale());
            first.binding.data.r1().mul(activeModelScale());
        }
        Vector3f position = new Vector3f(first.position).mul(activeModelScale());
        return new Vertex(position, first);
    }

    @Override
    public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) {
        return new Mesh(new Vertex[]{v1, v2, v3}, new Vector3f(), new Transform(), new Material[1], mesh);
    }

    @Override
    public Bone getBone(List<PmxBone> bones, PmxBone bone) {
        return new Bone(bone.localBoneName, super.calculateBoneTransform(bones, bone), bone);
    }

    @Override
    public void scaleBone(PmxBone bone) {
        bone.position.mul(activeModelScale());
        if (bone.tailObject instanceof Vector3f v) {
            v.mul(activeModelScale());
        }
    }

    @Override
    protected Vector3f scaleMmdTranslation(Vector3f value) {
        return value.mul(activeModelScale());
    }

    @Override
    public @Nullable ModelData getModelData(PmxHeader header) {
        return header;
    }

    @Override
    public @Nullable ModelData getModelData(PmxHeader header, PmxTail tail) {
        return new MmdModelData(header, tail, activeModelScale(), getBones().size());
    }

    private Vector3f activeModelScale() {
        return loadingFile == null
                ? new Vector3f(ZipMeta.DEFAULT_MODEL_SCALE)
                : loadingFile.getModelScale();
    }

    @Override
    public void beforeAllLoaders(ByteBuffer buffer, SerialContext<KsgPmxContext> context) {}

    @Override
    public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<KsgPmxContext> context) {}

    @Override
    public ByteBuffer getAsByteBuffer(ZipHelper input) {
        Objects.requireNonNull(loadingFile);
        Objects.requireNonNull(loadingModel);
        ByteBuffer buffer = loadingModel.buffer().duplicate();
        buffer.order(loadingModel.buffer().order());
        buffer.position(0);
        if (buffer.remaining() >= 3 && buffer.get(0) == 'P' && buffer.get(1) == 'm' && buffer.get(2) == 'd') {
            return new PmdToPmxConverter().convert(buffer);
        }
        return buffer;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ZipHelper;
    }

    @Override
    public @Nullable <T> T getSource(String type, String sourceManagerName, String sourceName) {
        return super.getSource(type, sourceManagerName, sourceName);
    }

    @Override
    public Texture loadTexture(Object textureIdentifier) {
        if (isTextureLoading()) {
            if (textureIdentifier == null) {
                loadedTextures.add(
                        Pair.of(
                                null, MISSING
                        )
                );
                return null;
            }
            return this.getTexture((ZipResource) textureIdentifier);
        } else if (textureIdentifier == null) {
            return null;
        }
        return loadedTextureMap.get(textureIdentifier);
    }

    public ResourceLocation getLocation(ResourceLocation fileLoc, ZipResource resource) {
        String filePath = fileLoc.getPath();
        String convertNameAsDir = filePath.endsWith(".pmx.zip")
                ? filePath.substring(0, filePath.length() - ".pmx.zip".length()) + "/"
                : filePath + "/";
        String resourceName = resource.name().toLowerCase(Locale.ROOT);
        ResourceLocation loc = ResourceLocation.tryBuild(
                fileLoc.getNamespace(),
                convertNameAsDir + resourceName
        );
        if (loc == null) {
            loc = ResourceLocation.tryBuild(
                    fileLoc.getNamespace(),
                    convertNameAsDir + Integer.toUnsignedString(resourceName.hashCode()) + ".pmx"
            );
        }
        return loc;
    }

    public ResourceLocation getLocByFileAndName(ResourceLocation fileLoc, String name) {
        String lowerName;
        for (ResourceLocation loc : loadedModelMap.keySet()) {
            if (!loc.equals(fileLoc)) continue;
            Map<String, ResourceLocation> map = loadedModelMap.get(loc);
            lowerName = name.toLowerCase(Locale.ROOT);
            if (map.containsKey(lowerName)) {
                return map.get(lowerName);
            }
        }
        return null;
    }

    @Override
    public Map<ResourceLocation, Model> load(ResourceLocation s, ZipHelper input) {
        loadedTextures.clear();
        loadedTextureMap.clear();
        loadedPbrTextureVariants.clear();
        loadingFile = input;
        try {
            List<ZipResource> models = input.searchNameForResource(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".pmx") || lower.endsWith(".pmd");
            });
            if (models.isEmpty()) return new HashMap<>();
            Map<ResourceLocation, Model> result = new HashMap<>();
            for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
                ZipResource model = models.get(modelIndex);
                LoadingIndicator.label("Loading MMD " + model.name() + " (" + (modelIndex + 1) + "/" + models.size() + ")");
                loadingModel = model;
                registerDefaultTextures();
                ResourceLocation rl = getLocation(s, model);
                loadingModelLocation = rl;
                loadingMaterialIndex = 0;
                Map<ResourceLocation, Model> loadedModels = super.load(rl, input);
                result.putAll(loadedModels);
                LOGGER.info("Loaded MMD model '{}' from {} as {} ({} model entries, {} textures)",
                        model.name(), s, rl, loadedModels.size(), loadedTextures.size());
                this.loadedModelMap.computeIfAbsent(s, k -> new HashMap<>())
                        .put(model.name().toLowerCase(Locale.ROOT), rl);
                loadedTextures.clear();
            }
            return result;
        } finally {
            loadingModel = null;
            loadingModelLocation = null;
            loadingMaterialIndex = 0;
            loadingFile = null;
            loadedTextures.clear();
            loadedTextureMap.clear();
            loadedPbrTextureVariants.clear();
            materialSetBuilder().clear();
        }
    }

    private void registerDefaultTextures() {
        materialSetBuilder().registerTexture(RenderState.DEFAULT_TRANSPARENCY, MISSING_TRANSPARENCY);
        materialSetBuilder().registerTexture(MissingTextureAtlasSprite.getLocation(), MISSING);
    }

    private byte[] copyBytes(ByteBuffer source) {
        ByteBuffer duplicate = source.duplicate();
        duplicate.position(0);
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }

    private record PbrTextureVariantKey(ZipResource source, PbrBakeProfile profile) {}

}
