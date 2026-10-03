package lib.kasuga.rendering.models.uml.typo.gltf;

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
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.util.*;

/** Converts format-level glTF data into the repository's common UML model. */
public final class GltfModelConverter {
    @FunctionalInterface
    public interface TextureFactory {
        Texture create(int imageIndex, GltfAsset.Texture texture);
    }

    private GltfModelConverter() {}

    public static Model convert(GltfAsset asset) {
        return convert(asset, new Vector3f(1f), (index, texture) -> new Texture(texture.name(),
                texture.image().getWidth(), texture.image().getHeight(),
                new GltfModelData.GltfTextureData(texture.name(), texture.image())));
    }

    public static Model convert(GltfAsset asset, TextureFactory textureFactory) {
        return convert(asset, new Vector3f(1f), textureFactory);
    }

    public static Model convert(GltfAsset asset, Vector3f modelScale, TextureFactory textureFactory) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(modelScale, "modelScale");
        Objects.requireNonNull(textureFactory, "textureFactory");
        if (!modelScale.isFinite() || modelScale.x <= 0f || modelScale.y <= 0f || modelScale.z <= 0f) {
            throw new IllegalArgumentException("modelScale components must be finite and positive");
        }
        GltfAsset.NodeHierarchy nodes = asset.nodes();
        Bone[] nodeBones = new Bone[nodes.size()];
        Set<String> usedNames = new HashSet<>();
        for (int node = 0; node < nodes.size(); node++) {
            String base = nodes.names()[node];
            String name = usedNames.add(base) ? base : base + "#" + node;
            Matrix4f local = new Matrix4f().translationRotateScale(nodes.translations()[node],
                    nodes.rotations()[node], nodes.scales()[node]);
            if (nodes.parents()[node] < 0) local = new Matrix4f().scaling(modelScale).mul(local);
            nodeBones[node] = new Bone(name, new Transform().set(local), null);
        }
        List<Bone> roots = new ArrayList<>();
        Map<Bone, List<Bone>> children = new IdentityHashMap<>();
        for (int node = 0; node < nodeBones.length; node++) {
            int parent = nodes.parents()[node];
            if (parent < 0) {
                roots.add(nodeBones[node]);
            } else {
                nodeBones[node].setParent(nodeBones[parent]);
                children.computeIfAbsent(nodeBones[parent], ignored -> new ArrayList<>()).add(nodeBones[node]);
            }
        }
        children.forEach((parent, values) -> parent.setChildren(values.toArray(Bone[]::new)));
        Bone root;
        Bone[] bones;
        if (roots.size() == 1) {
            root = roots.getFirst();
            bones = nodeBones;
        } else {
            root = new Bone("__gltf_root__", new Transform(), null);
            root.setChildren(roots.toArray(Bone[]::new));
            roots.forEach(value -> value.setParent(root));
            bones = new Bone[nodeBones.length + 1];
            bones[0] = root;
            System.arraycopy(nodeBones, 0, bones, 1, nodeBones.length);
        }
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        Map<Integer, Bone> boneByNode = new HashMap<>();
        for (int node = 0; node < nodeBones.length; node++) boneByNode.put(node, nodeBones[node]);

        List<Texture> textures = new ArrayList<>();
        for (int i = 0; i < asset.textures().size(); i++) {
            textures.add(Objects.requireNonNull(textureFactory.create(i, asset.textures().get(i))));
        }
        if (textures.isEmpty()) {
            BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            white.setRGB(0, 0, 0xffffffff);
            textures.add(new Texture("__gltf_white__", 1, 1,
                    new GltfModelData.GltfTextureData("__gltf_white__", white)));
        }
        Map<GltfAsset.Material, Material> materialCache = new HashMap<>();
        List<Vertex> allVertices = new ArrayList<>();
        List<Mesh> allMeshes = new ArrayList<>();

        for (GltfAsset.Primitive primitive : asset.primitives()) {
            Material material = materialCache.computeIfAbsent(primitive.material(), definition ->
                    createMaterial(definition, textures));
            Vertex[] vertices = new Vertex[primitive.vertexCount()];
            Vector3f[] normals = new Vector3f[primitive.vertexCount()];
            Matrix4f world = new Matrix4f().scaling(modelScale).mul(primitive.nodeWorld());
            Matrix3f normalMatrix = new Matrix3f(world).invert().transpose();
            for (int vertexIndex = 0; vertexIndex < vertices.length; vertexIndex++) {
                int p = vertexIndex * 3;
                Vector3f position = world.transformPosition(new Vector3f(
                        primitive.positions()[p], primitive.positions()[p + 1], primitive.positions()[p + 2]));
                normals[vertexIndex] = normalMatrix.transform(new Vector3f(
                        primitive.normals()[p], primitive.normals()[p + 1], primitive.normals()[p + 2])).normalize();
                Vertex vertex = new Vertex(position, null);
                vertex.setBinding(binding(asset, primitive, vertexIndex, nodeBones, root));
                vertices[vertexIndex] = vertex;
                allVertices.add(vertex);
            }
            for (int index = 0; index + 2 < primitive.indices().length; index += 3) {
                int ia = primitive.indices()[index];
                int ib = primitive.indices()[index + 1];
                int ic = primitive.indices()[index + 2];
                if (ia < 0 || ib < 0 || ic < 0 || ia >= vertices.length || ib >= vertices.length || ic >= vertices.length) {
                    throw new IllegalArgumentException("glTF primitive contains an out-of-range vertex index");
                }
                Mesh mesh = new Mesh(new Vertex[]{vertices[ia], vertices[ib], vertices[ic]},
                        new Vector3f(normals[ia]).add(normals[ib]).add(normals[ic]).normalize(),
                        new Transform(), new Material[]{material},
                        new GltfModelData.GltfMeshData(primitive.name(), primitive.nodeIndex(), primitive.skinIndex()));
                mesh.setCulled(!primitive.material().doubleSided());
                addCorner(vertices[ia], mesh, material, primitive.texCoords(), ia, normals[ia]);
                addCorner(vertices[ib], mesh, material, primitive.texCoords(), ib, normals[ib]);
                addCorner(vertices[ic], mesh, material, primitive.texCoords(), ic, normals[ic]);
                allMeshes.add(mesh);
            }
        }
        MaterialSet materialSet = new MaterialSet(textures, materialCache.values());
        return new Model(allVertices.toArray(Vertex[]::new), allMeshes.toArray(Mesh[]::new),
                bones, skeleton, materialSet, MeshMode.TRIANGLES,
                new GltfModelData(asset, boneByNode, modelScale), null);
    }

    private static Material createMaterial(GltfAsset.Material definition, List<Texture> textures) {
        int textureIndex = definition.textureIndex();
        Texture texture = textureIndex >= 0 && textureIndex < textures.size()
                ? textures.get(textureIndex) : textures.getFirst();
        Material material = new Material(new Texture[]{texture},
                new GltfModelData.GltfMaterialData(definition));
        material.hookTextures();
        Sprite sprite = new Sprite(texture, new Vector2f(0, 0), new Vector2f(1, 0),
                new Vector2f(1, 1), new Vector2f(0, 1), definition.baseColor(),
                null, null, null);
        sprite.culled = !definition.doubleSided();
        material.addSprite(new SpriteSet(null, sprite));
        return material;
    }

    @SuppressWarnings("unchecked")
    private static BoneBinding binding(GltfAsset asset, GltfAsset.Primitive primitive, int vertex,
                                       Bone[] nodeBones, Bone fallback) {
        // glTF permits float weights whose sum is not exactly one. Some
        // exporters also duplicate a joint component. Canonicalize both here
        // before entering the format-neutral skinning path; PMX BDEF/SDEF/QDEF
        // semantics must not leak into glTF just because both use four slots.
        Map<Bone, Double> mergedWeights = new LinkedHashMap<>(4);
        if (primitive.skinned() && primitive.skinIndex() < asset.skins().size()) {
            GltfAsset.Skin skin = asset.skins().get(primitive.skinIndex());
            for (int component = 0; component < 4; component++) {
                int offset = vertex * 4 + component;
                float weight = primitive.weights()[offset];
                int joint = primitive.joints()[offset];
                if (!Float.isFinite(weight) || weight <= 0f
                        || joint < 0 || joint >= skin.jointNodeIndices().length) continue;
                int node = skin.jointNodeIndices()[joint];
                if (node >= 0 && node < nodeBones.length) {
                    mergedWeights.merge(nodeBones[node], (double) weight, Double::sum);
                }
            }
        }
        List<Pair<Bone, Float>> weights = new ArrayList<>(mergedWeights.size());
        double sum = mergedWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0.0 && Double.isFinite(sum)) {
            mergedWeights.forEach((bone, weight) -> weights.add(Pair.of(bone, (float) (weight / sum))));
        }
        if (weights.isEmpty()) {
            Bone bone = primitive.nodeIndex() >= 0 && primitive.nodeIndex() < nodeBones.length
                    ? nodeBones[primitive.nodeIndex()] : fallback;
            weights.add(Pair.of(bone, 1f));
        }
        return new BoneBinding(weights.toArray(Pair[]::new), BoneBindingFunc.BDEF, null);
    }

    private static void addCorner(Vertex vertex, Mesh mesh, Material material,
                                  float[] texCoords, int index, Vector3f normal) {
        int uv = index * 2;
        vertex.getUvs().computeIfAbsent(mesh, ignored -> new HashMap<>())
                .put(material, new Vector2f(texCoords[uv], texCoords[uv + 1]));
        vertex.getNormals().put(mesh, new Vector3f(normal));
    }
}
