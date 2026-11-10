package lib.kasuga.rendering.models.mc.typo;

import com.mojang.blaze3d.platform.NativeImage;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.IdentifierHelper;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;

import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjTextureData;
import lib.kasuga.structure.Pair;
import lombok.NonNull;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.joml.Vector2f;
import org.joml.Vector3f;

public class KsgObjLoader extends ObjModelLoader<String, ResourceLocation, String> {

    private final HashMap<String, ResourceLocation> loadedModels;

    private final HashMap<String, lib.kasuga.rendering.models.uml.structure.material.Material> materials;
    private final HashMap<ResourceLocation, TextureAlpha> textureAlphas;

    public KsgObjLoader(String name) {
        super(name, true, true, true);
        loadedModels = new HashMap<>();
        materials = new HashMap<>();
        textureAlphas = new HashMap<>();
    }

    /** Uses the standard OBJ/MTL processors, exporting a custom interleaved GPU stream. */
    public static FlatMesh loadFlatMesh(String objSource, String mtlSource, Function<String, Float> kindResolver) {
        var loader = new FlatLoader(mtlSource, kindResolver);
        loader.load(ResourceLocation.fromNamespaceAndPath("kasuga_lib", "flat_mesh"), objSource);
        return loader.result;
    }

    public record FlatMesh(float[] vertices, int triangleCount, int vertexCount,
                           float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                           Map<String, ObjTextureData> materials) {}

    /** Output adapter only: tokenization, indices, groups and MTL all use the existing loader. */
    private static final class FlatLoader extends KsgObjLoader {
        private final String materialSource;
        private final Function<String, Float> kindResolver;
        private final Map<String, ObjTextureData> flatMaterials = new LinkedHashMap<>();
        private final List<FlatFace> faces = new ArrayList<>();
        private FlatMesh result;

        FlatLoader(String materialSource, Function<String, Float> kindResolver) {
            super("flat_obj");
            this.materialSource = materialSource;
            this.kindResolver = kindResolver;
        }

        @Override public String getTextureContent(String ignored) { return materialSource; }
        @Override public void consumeTexture(ObjTextureData material) {
            flatMaterials.put(material.getName(), material);
        }
        @Override public void beforeProcessors(lib.kasuga.rendering.models.uml.loaders.serial.SerialContext<ObjContextData> context) {
            getMtlLoader().readMtlFile(materialSource);
        }
        @Override public void endHighestGroup(lib.kasuga.rendering.models.uml.loaders.serial.SerialContext<ObjContextData> context) {
            while (!context.isEmpty()) {
                var group = context.pop();
                for (int i = 0; i < group.getBindings().size(); i++) {
                    var corners = group.getBindings().get(i);
                    if (corners.size() < 3) throw new IllegalArgumentException("OBJ surface requires at least three corners");
                    for (int j = 1; j + 1 < corners.size(); j++) {
                        faces.add(new FlatFace(List.of(corners.getFirst(), corners.get(j), corners.get(j + 1)),
                                group.getBindingMaterials().get(i)));
                    }
                }
                if (group.isGroup()) break;
            }
        }
        @Override public void build(HashMap<ResourceLocation, Model> unused) {
            while (!getContext().isEmpty()) endHighestGroup(getContext());
            if (faces.isEmpty()) throw new IllegalArgumentException("OBJ contains no surfaces");
            float[] vertices = new float[faces.size() * 3 * 16];
            int out = 0;
            var min = new Vector3f(Float.POSITIVE_INFINITY);
            var max = new Vector3f(Float.NEGATIVE_INFINITY);
            for (FlatFace face : faces) {
                var material = flatMaterials.get(face.material());
                if (material == null) throw new IllegalArgumentException("Undefined OBJ material: " + face.material());
                var a = getObjVertexPositions().get(face.corners().get(0).vertexIndex());
                var b = getObjVertexPositions().get(face.corners().get(1).vertexIndex());
                var c = getObjVertexPositions().get(face.corners().get(2).vertexIndex());
                var faceNormal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
                if (faceNormal.lengthSquared() < 1e-20f) throw new IllegalArgumentException("Degenerate OBJ surface");
                faceNormal.normalize();
                for (var corner : face.corners()) {
                    var position = getObjVertexPositions().get(corner.vertexIndex());
                    var normal = resolveNormal(corner, faceNormal);
                    var uv = corner.textureIndex() < 0 ? new Vector2f() : getObjVertexUvs().get(corner.textureIndex());
                    if (!position.isFinite() || !normal.isFinite() || !uv.isFinite())
                        throw new IllegalArgumentException("Non-finite OBJ vertex attributes");
                    vertices[out++] = position.x; vertices[out++] = position.y; vertices[out++] = position.z;
                    vertices[out++] = normal.x; vertices[out++] = normal.y; vertices[out++] = normal.z;
                    vertices[out++] = uv.x; vertices[out++] = uv.y;
                    vertices[out++] = material.getKd().x; vertices[out++] = material.getKd().y;
                    vertices[out++] = material.getKd().z; vertices[out++] = material.getD();
                    vertices[out++] = material.getKe().length(); vertices[out++] = material.getPr();
                    vertices[out++] = kindResolver == null ? 0 : kindResolver.apply(face.material());
                    vertices[out++] = 0;
                    min.min(position); max.max(position);
                }
            }
            result = new FlatMesh(vertices, faces.size(), faces.size() * 3,
                    min.x, min.y, min.z, max.x, max.y, max.z, Map.copyOf(flatMaterials));
            clear();
        }

        private Vector3f resolveNormal(
                lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjVertexBinding corner,
                Vector3f faceNormal
        ) {
            if (corner.normalIndex() < 0) {
                return new Vector3f(faceNormal);
            }
            Vector3f normal = getObjVertexNormals().get(corner.normalIndex());
            if (normal == null
                    || !Float.isFinite(normal.x())
                    || !Float.isFinite(normal.y())
                    || !Float.isFinite(normal.z())
                    || normal.lengthSquared() <= 1e-12f) {
                return new Vector3f(faceNormal);
            }
            return new Vector3f(normal).normalize();
        }
    }

    private record FlatFace(List<lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjVertexBinding> corners,
                            String material) {}

    @Override
    public Map<ResourceLocation, Model> load(
            ResourceLocation identifier, String input
    ) {
        loadedModels.put(input, identifier);
        return super.load(identifier, input);
    }

    @Override
    public @Nullable VertexData getVertexData(ObjContextData data, ObjModelLoader loader, String boneName) {
        return null;
    }

    @Override
    public @Nullable MeshData getMeshData(ObjContextData data, ObjModelLoader loader) {
        return null;
    }

    @Override
    public @NonNull Texture getTexture(ObjContextData data, ObjModelLoader loader, String mtlName) {
        return materialSetBuilder().getTexture(mtlName);
    }

    @Override
    public lib.kasuga.rendering.models.uml.structure.material.@NonNull Material getMaterial(ObjContextData data, ObjModelLoader loader, String mtlName) {
        lib.kasuga.rendering.models.uml.structure.material.Material material = materials.get(mtlName);
        if (material != null) {
            return material;
        }
        return createFallbackMaterial(mtlName);
    }

    @Override
    public @Nullable BoneData getBoneData(ObjModelLoader loader, String boneName) {
        return null;
    }

    @Override
    public @Nullable BoneBindingData getBoneBindingData(ObjModelLoader loader, Bone bone, Vertex vertex) {
        return null;
    }

    @Override
    public @Nullable SkeletonData getSkeletonData(ObjModelLoader loader, Bone[] bones) {
        return null;
    }

    @Override
    public @Nullable ModelData getModelData(ObjModelLoader loader) {
        return null;
    }

    @Override
    public @NotNull ResourceLocation getIdentifier(ObjModelLoader loader, String input) {
        return loadedModels.getOrDefault(input, ResourceLocation.tryBuild("kasuga_lib", "missingno"));
    }

    @Override
    public @NonNull Anchor[] getAnchors(ObjModelLoader loader, Bone[] bones, Bone rootBone) {
        return new Anchor[0];
    }

    @Override
    public void consumeTexture(ObjTextureData texture) {
        if (texture == null) return;
        String map_kd = texture.getMap_Kd();
        if (map_kd == null) {
            createFallbackMaterial(texture.getName());
            return;
        }
        Pair<ResourceLocation, Path> pair = IdentifierHelper.getRLAndPath(map_kd);
        if (pair == null) {
            createFallbackMaterial(texture.getName());
            return;
        }
        ResourceLocation rl = pair.getFirst();
        Object identifier = pair.getSecond() == null ? rl : pair.getSecond();
        KasugaTextureManager textureManager = getTextureManager();
        if (textureManager == null) {
            createFallbackMaterial(texture.getName());
            return;
        }
        textureManager.load(identifier);
        MCTextureData textureData = new MCTextureData(
                identifier, textureManager
        );
        Material material = new Material(RenderState.KSG_LAYER_0, rl);
        MCTexture tex = new MCTexture(texture.getName(), () -> material, 1, 1, textureData);
        materialSetBuilder()
                .registerTexture(texture.getName(), tex)
                .useTexture(texture.getName())
                .setMaterialData(resolveMaterialData(texture, rl))
                .addSpriteBuildingFunc((mtlb, sprb, mtl) -> {
                    sprb.textureId(texture.getName()).flipV(true).endSprite();
                }).endMaterial();
        materials.put(texture.getName(), materialSetBuilder().getMaterials().getLast());
    }

    private lib.kasuga.rendering.models.uml.structure.material.Material createFallbackMaterial(String materialName) {
        String name = materialName == null ? "__default__" : materialName;
        lib.kasuga.rendering.models.uml.structure.material.Material existing = materials.get(name);
        if (existing != null) {
            if (materialName == null) {
                materials.put(null, existing);
            }
            return existing;
        }
        String textureId = "__missing_obj_texture__/" + name;
        ResourceLocation rl = MissingTextureAtlasSprite.getLocation();
        KasugaTextureManager textureManager = getTextureManager();
        if (textureManager == null) {
            textureManager = Constants.TEXTURE_BASIC;
        }
        MCTextureData textureData = new MCTextureData(rl, textureManager);
        Material material = new Material(RenderState.KSG_LAYER_0, rl);
        MCTexture tex = new MCTexture(name, () -> material, 16, 16, textureData);
        materialSetBuilder()
                .registerTexture(textureId, tex)
                .useTexture(textureId)
                .setMaterialData(ObjMaterialData.from(1f, true, false))
                .addSpriteBuildingFunc((mtlb, sprb, mtl) -> sprb.textureId(textureId).endSprite())
                .endMaterial();
        lib.kasuga.rendering.models.uml.structure.material.Material created = materialSetBuilder().getMaterials().getLast();
        materials.put(name, created);
        if (materialName == null) {
            materials.put(null, created);
        }
        return created;
    }

    private ObjMaterialData resolveMaterialData(ObjTextureData texture, ResourceLocation textureLocation) {
        TextureAlpha alpha = textureAlphas.computeIfAbsent(
                textureLocation,
                this::readTextureAlpha
        );
        ObjMaterialData materialData = ObjMaterialData.from(
                texture.getD(),
                alpha.hasTransparentPixels(),
                alpha.hasPartialTransparency()
        );
        return materialData;
    }

    private TextureAlpha readTextureAlpha(ResourceLocation textureLocation) {
        String path = textureLocation.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        ResourceLocation imageLocation = ResourceLocation.tryBuild(
                textureLocation.getNamespace(),
                path
        );
        if (imageLocation == null) return TextureAlpha.UNKNOWN;

        Optional<InputStream> stream = IdentifierHelper.getInputStream(imageLocation);
        if (stream.isEmpty()) return TextureAlpha.UNKNOWN;
        try (InputStream input = stream.get(); NativeImage image = NativeImage.read(input)) {
            boolean transparent = false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getPixelRGBA(x, y) >>> 24;
                    if (alpha == 255) continue;
                    transparent = true;
                    if (alpha != 0) {
                        return new TextureAlpha(true, true);
                    }
                }
            }
            return new TextureAlpha(transparent, false);
        } catch (IOException | RuntimeException exception) {
            return TextureAlpha.UNKNOWN;
        }
    }

    private record TextureAlpha(boolean hasTransparentPixels, boolean hasPartialTransparency) {
        private static final TextureAlpha UNKNOWN = new TextureAlpha(false, false);
    }

    @Nullable
    private KasugaTextureManager getTextureManager() {
        HashMap<String, SourceManager<?>> managers = this.getSidedSources().get(Constants.TEXTURE_TYPE);
        if (managers == null) return Constants.TEXTURE_BASIC;
        SourceManager<?> manager = managers.get("mc_layer_0");
        if (manager instanceof KasugaTextureManager textureManager) {
            return textureManager;
        }
        return Constants.TEXTURE_BASIC;
    }

    @Override
    public String getTextureContent(String mtlUrl) {
        Optional<InputStream> stream = openMtl(mtlUrl);
        if (stream.isEmpty()) {
            stream = openSiblingMtl(mtlUrl);
        }
        return stream.map(KsgObjLoader::readAll).orElse("");
    }

    private Optional<InputStream> openMtl(String mtlUrl) {
        Pair<ResourceLocation, Path> pair = IdentifierHelper.getRLAndPath(mtlUrl);
        if (pair == null) {
            return Optional.empty();
        }
        if (pair.getSecond() != null) {
            return IdentifierHelper.getInputStream(pair.getSecond());
        }
        ResourceLocation location = ResourceLocation.tryBuild(
                pair.getFirst().getNamespace(),
                "models/" + pair.getFirst().getPath() + ".mtl"
        );
        return location == null ? Optional.empty() : IdentifierHelper.getInputStream(location);
    }

    /**
     * Blockbench exports write {@code mtllib pk209p_main.mtl} without a namespace. The resolver above
     * turns that into {@code minecraft:models/pk209p_main.mtl}, which never exists; resolve it next to
     * the OBJ that declared it instead.
     */
    private Optional<InputStream> openSiblingMtl(String mtlUrl) {
        if (mtlUrl == null || mtlUrl.indexOf(':') >= 0 || getCurrentInput() == null) {
            return Optional.empty();
        }
        ResourceLocation owner = loadedModels.get(getCurrentInput());
        if (owner == null) {
            return Optional.empty();
        }
        String path = owner.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        ResourceLocation sibling = ResourceLocation.tryBuild(
                owner.getNamespace(), path.substring(0, slash + 1) + mtlUrl);
        return sibling == null ? Optional.empty() : IdentifierHelper.getInputStream(sibling);
    }

    private static String readAll(InputStream stream) {
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public String getAsString(String input) {
        return input;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof String;
    }

    @Override
    public void clear() {
        super.clear();
        loadedModels.clear();
        materials.clear();
    }

    @Override
    public Texture loadTexture(Object textureIdentifier) {
        return getTexture(getContext().peek(), this, (String) textureIdentifier);
    }
}
