package lib.kasuga.rendering.models.uml.typo.gltf;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Node;
import de.javagl.jgltf.model.*;
import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.GltfModelReader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;

/** glTF 2.0 / GLB parser used by the modelling pipeline. */
public final class GltfLoader {
    private static final int TRIANGLES = 4;

    private GltfLoader() {}

    /** Loads geometry, materials and skins without decoding animation tracks. */
    public static GltfAsset load(Path file) throws IOException {
        return load(file, Set.of());
    }

    /** Loads geometry and only the named animation clips. */
    public static GltfAsset load(Path file, Set<String> animationNames) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(animationNames, "animationNames");
        GlTF raw = (GlTF)new GltfAssetReader().read(file).getGltf();
        GltfModel model = new GltfModelReader().read(file);
        return build(raw, model, animationNames);
    }

    /** Loads an embedded glTF or GLB stream (external URI references are not resolved). */
    public static GltfAsset load(InputStream input, Set<String> animationNames) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readAllBytes();
        GlTF raw = (GlTF)new GltfAssetReader()
                .readWithoutReferences(new ByteArrayInputStream(bytes)).getGltf();
        GltfModel model = new GltfModelReader()
                .readWithoutReferences(new ByteArrayInputStream(bytes));
        return build(raw, model, animationNames);
    }

    public static GltfAsset load(InputStream input) throws IOException {
        return load(input, Set.of());
    }

    public static GltfAsset loadAllAnimations(InputStream input) throws IOException {
        byte[] bytes = Objects.requireNonNull(input, "input").readAllBytes();
        GlTF raw = (GlTF)new GltfAssetReader()
                .readWithoutReferences(new ByteArrayInputStream(bytes)).getGltf();
        Set<String> names = animationNames(raw);
        return load(new ByteArrayInputStream(bytes), names);
    }

    private static GltfAsset build(GlTF raw, GltfModel model, Set<String> animationNames) throws IOException {
        GltfAsset.NodeHierarchy hierarchy = buildHierarchy(raw);
        List<GltfAsset.Texture> textures = loadTextures(raw, model);
        List<GltfAsset.Primitive> primitives = loadPrimitives(raw, model, hierarchy);
        List<GltfAsset.Skin> skins = loadSkins(raw, model, hierarchy.parents());
        List<GltfAsset.AnimationClip> animations = loadAnimations(raw, model, animationNames);
        return new GltfAsset(primitives, textures, skins, hierarchy, animations);
    }

    /** Convenience path for tools that intentionally need every clip. */
    public static GltfAsset loadAllAnimations(Path file) throws IOException {
        GlTF raw = (GlTF)new GltfAssetReader().read(file).getGltf();
        return load(file, animationNames(raw));
    }

    private static Set<String> animationNames(GlTF raw) {
        Set<String> names = new LinkedHashSet<>();
        if (raw.getAnimations() != null) {
            for (int i = 0; i < raw.getAnimations().size(); i++) {
                String name = raw.getAnimations().get(i).getName();
                names.add(name == null || name.isBlank() ? "animation_" + i : name);
            }
        }
        return names;
    }

    private static GltfAsset.NodeHierarchy buildHierarchy(GlTF raw) {
        List<Node> nodes = raw.getNodes() == null ? List.of() : raw.getNodes();
        int count = nodes.size();
        String[] names = new String[count];
        int[] parents = new int[count];
        Arrays.fill(parents, -1);
        Vector3f[] translations = new Vector3f[count];
        Quaternionf[] rotations = new Quaternionf[count];
        Vector3f[] scales = new Vector3f[count];
        Matrix4f[] worlds = new Matrix4f[count];
        for (int i = 0; i < count; i++) {
            Node node = nodes.get(i);
            names[i] = node.getName() == null || node.getName().isBlank() ? "node_" + i : node.getName();
            Matrix4f local = localMatrix(node);
            translations[i] = local.getTranslation(new Vector3f());
            rotations[i] = local.getUnnormalizedRotation(new Quaternionf()).normalize();
            scales[i] = local.getScale(new Vector3f());
            worlds[i] = new Matrix4f();
            if (node.getChildren() != null) {
                for (int child : node.getChildren()) if (child >= 0 && child < count) parents[child] = i;
            }
        }
        List<Integer> order = new ArrayList<>(count);
        boolean[] visited = new boolean[count];
        for (int i = 0; i < count; i++) if (parents[i] < 0) visit(i, nodes, visited, order);
        for (int i = 0; i < count; i++) visit(i, nodes, visited, order);
        for (int node : order) {
            Matrix4f local = new Matrix4f().translationRotateScale(
                    translations[node], rotations[node], scales[node]);
            worlds[node].set(parents[node] < 0 ? local : new Matrix4f(worlds[parents[node]]).mul(local));
        }
        return new GltfAsset.NodeHierarchy(names, parents,
                order.stream().mapToInt(Integer::intValue).toArray(),
                translations, rotations, scales, worlds);
    }

    private static void visit(int index, List<Node> nodes, boolean[] visited, List<Integer> order) {
        if (index < 0 || index >= nodes.size() || visited[index]) return;
        visited[index] = true;
        order.add(index);
        List<Integer> children = nodes.get(index).getChildren();
        if (children != null) for (int child : children) visit(child, nodes, visited, order);
    }

    private static Matrix4f localMatrix(Node node) {
        if (node.getMatrix() != null) return new Matrix4f().set(node.getMatrix());
        float[] t = node.getTranslation() == null ? new float[]{0, 0, 0} : node.getTranslation();
        float[] r = node.getRotation() == null ? new float[]{0, 0, 0, 1} : node.getRotation();
        float[] s = node.getScale() == null ? new float[]{1, 1, 1} : node.getScale();
        return new Matrix4f().translationRotateScale(new Vector3f(t[0], t[1], t[2]),
                new Quaternionf(r[0], r[1], r[2], r[3]), new Vector3f(s[0], s[1], s[2]));
    }

    private static List<GltfAsset.Texture> loadTextures(GlTF raw, GltfModel model) throws IOException {
        if (raw.getImages() == null) return List.of();
        List<GltfAsset.Texture> result = new ArrayList<>(raw.getImages().size());
        for (int i = 0; i < raw.getImages().size(); i++) {
            ByteBuffer source = model.getImageModels().get(i).getImageData().duplicate();
            byte[] encoded = new byte[source.remaining()];
            source.get(encoded);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null) throw new IOException("Unable to decode glTF image " + i);
            String name = raw.getImages().get(i).getName();
            result.add(new GltfAsset.Texture(name == null ? "image_" + i : name, image));
        }
        return result;
    }

    private static List<GltfAsset.Primitive> loadPrimitives(GlTF raw, GltfModel model,
                                                             GltfAsset.NodeHierarchy hierarchy) {
        if (raw.getNodes() == null || raw.getMeshes() == null) return List.of();
        List<GltfAsset.Primitive> result = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < raw.getNodes().size(); nodeIndex++) {
            Node node = raw.getNodes().get(nodeIndex);
            if (node.getMesh() == null || node.getMesh() < 0 || node.getMesh() >= raw.getMeshes().size()) continue;
            var mesh = raw.getMeshes().get(node.getMesh());
            String baseName = node.getName() == null || node.getName().isBlank()
                    ? mesh.getName() == null || mesh.getName().isBlank() ? "mesh_" + node.getMesh() : mesh.getName()
                    : node.getName();
            for (int primitiveIndex = 0; primitiveIndex < mesh.getPrimitives().size(); primitiveIndex++) {
                var primitive = mesh.getPrimitives().get(primitiveIndex);
                if (primitive.getMode() != null && primitive.getMode() != TRIANGLES) continue;
                Map<String, Integer> attributes = primitive.getAttributes();
                if (attributes == null || !attributes.containsKey("POSITION")) continue;
                AccessorFloatData positions = AccessorDatas.createFloat(
                        model.getAccessorModels().get(attributes.get("POSITION")));
                int vertexCount = positions.getNumElements();
                float[] positionValues = floatValues(positions, 3);
                float[] normalValues;
                if (attributes.containsKey("NORMAL")) {
                    normalValues = floatValues(AccessorDatas.createFloat(
                            model.getAccessorModels().get(attributes.get("NORMAL"))), 3);
                } else {
                    normalValues = new float[vertexCount * 3];
                }
                float[] uvValues = attributes.containsKey("TEXCOORD_0")
                        ? floatValues(AccessorDatas.createFloat(model.getAccessorModels()
                        .get(attributes.get("TEXCOORD_0"))), 2) : new float[vertexCount * 2];
                int[] indices = primitive.getIndices() == null
                        ? java.util.stream.IntStream.range(0, vertexCount).toArray()
                        : intValues(AccessorDatas.create(model.getAccessorModels().get(primitive.getIndices())), 1);
                if (!attributes.containsKey("NORMAL")) generateNormals(positionValues, indices, normalValues);
                int[] joints = attributes.containsKey("JOINTS_0")
                        ? intValues(AccessorDatas.create(model.getAccessorModels().get(attributes.get("JOINTS_0"))), 4)
                        : new int[0];
                float[] weights = attributes.containsKey("WEIGHTS_0")
                        ? numberValues(AccessorDatas.create(model.getAccessorModels().get(attributes.get("WEIGHTS_0"))),
                        model.getAccessorModels().get(attributes.get("WEIGHTS_0")).isNormalized(), 4)
                        : new float[0];
                String name = mesh.getPrimitives().size() > 1 ? baseName + ":" + primitiveIndex : baseName;
                result.add(new GltfAsset.Primitive(name, nodeIndex,
                        node.getSkin() == null ? -1 : node.getSkin(), positionValues, normalValues,
                        uvValues, indices, joints, weights, hierarchy.bindWorlds()[nodeIndex],
                        material(raw, primitive.getMaterial())));
            }
        }
        return result;
    }

    private static GltfAsset.Material material(GlTF raw, Integer materialIndex) {
        if (materialIndex == null || raw.getMaterials() == null
                || materialIndex < 0 || materialIndex >= raw.getMaterials().size()) {
            return new GltfAsset.Material(-1, GltfAsset.AlphaMode.OPAQUE,
                    new Vector4f(1f), 1f, 1f, 0.5f, false);
        }
        var source = raw.getMaterials().get(materialIndex);
        var pbr = source.getPbrMetallicRoughness();
        float[] factor = pbr == null || pbr.getBaseColorFactor() == null
                ? new float[]{1, 1, 1, 1} : pbr.getBaseColorFactor();
        int imageIndex = -1;
        if (pbr != null && pbr.getBaseColorTexture() != null && raw.getTextures() != null) {
            int textureIndex = pbr.getBaseColorTexture().getIndex();
            if (textureIndex >= 0 && textureIndex < raw.getTextures().size()
                    && raw.getTextures().get(textureIndex).getSource() != null) {
                imageIndex = raw.getTextures().get(textureIndex).getSource();
            }
        }
        GltfAsset.AlphaMode alpha = switch (source.getAlphaMode() == null ? "OPAQUE" : source.getAlphaMode()) {
            case "MASK" -> GltfAsset.AlphaMode.MASK;
            case "BLEND" -> GltfAsset.AlphaMode.BLEND;
            default -> GltfAsset.AlphaMode.OPAQUE;
        };
        return new GltfAsset.Material(imageIndex, alpha,
                new Vector4f(factor[0], factor[1], factor[2], factor[3]),
                pbr == null || pbr.getMetallicFactor() == null ? 1f : pbr.getMetallicFactor(),
                pbr == null || pbr.getRoughnessFactor() == null ? 1f : pbr.getRoughnessFactor(),
                source.getAlphaCutoff() == null ? 0.5f : source.getAlphaCutoff(),
                Boolean.TRUE.equals(source.isDoubleSided()));
    }

    private static List<GltfAsset.Skin> loadSkins(GlTF raw, GltfModel model, int[] nodeParents) {
        if (raw.getSkins() == null) return List.of();
        List<GltfAsset.Skin> result = new ArrayList<>();
        List<Node> nodes = raw.getNodes() == null ? List.of() : raw.getNodes();
        for (var skin : raw.getSkins()) {
            int[] joints = skin.getJoints() == null ? new int[0]
                    : skin.getJoints().stream().mapToInt(Integer::intValue).toArray();
            Map<Integer, Integer> jointByNode = new HashMap<>();
            for (int i = 0; i < joints.length; i++) jointByNode.put(joints[i], i);
            String[] names = new String[joints.length];
            int[] parents = new int[joints.length];
            Matrix4f[] inverseBinds = new Matrix4f[joints.length];
            AccessorFloatData inverseData = skin.getInverseBindMatrices() == null ? null
                    : AccessorDatas.createFloat(model.getAccessorModels().get(skin.getInverseBindMatrices()));
            for (int i = 0; i < joints.length; i++) {
                int node = joints[i];
                names[i] = node >= 0 && node < nodes.size() && nodes.get(node).getName() != null
                        ? nodes.get(node).getName() : "joint_" + node;
                int parent = node >= 0 && node < nodeParents.length ? nodeParents[node] : -1;
                while (parent >= 0 && !jointByNode.containsKey(parent)) parent = nodeParents[parent];
                parents[i] = jointByNode.getOrDefault(parent, -1);
                inverseBinds[i] = inverseData == null ? new Matrix4f()
                        : new Matrix4f().set(floatElement(inverseData, i, 16));
            }
            result.add(new GltfAsset.Skin(joints, names, parents, inverseBinds));
        }
        return result;
    }

    private static List<GltfAsset.AnimationClip> loadAnimations(GlTF raw, GltfModel model,
                                                                 Set<String> requested) {
        if (requested.isEmpty() || raw.getAnimations() == null) return List.of();
        List<GltfAsset.AnimationClip> result = new ArrayList<>();
        Map<Integer, float[]> inputCache = new HashMap<>();
        for (int animationIndex = 0; animationIndex < raw.getAnimations().size(); animationIndex++) {
            var animation = raw.getAnimations().get(animationIndex);
            String name = animation.getName() == null || animation.getName().isBlank()
                    ? "animation_" + animationIndex : animation.getName();
            if (!requested.contains(name)) continue;
            List<GltfAsset.AnimationTrack> tracks = new ArrayList<>();
            if (animation.getChannels() == null || animation.getSamplers() == null) continue;
            for (var channel : animation.getChannels()) {
                if (channel.getTarget() == null || channel.getTarget().getNode() == null) continue;
                GltfAsset.AnimationPath path = switch (channel.getTarget().getPath()) {
                    case "translation" -> GltfAsset.AnimationPath.TRANSLATION;
                    case "rotation" -> GltfAsset.AnimationPath.ROTATION;
                    case "scale" -> GltfAsset.AnimationPath.SCALE;
                    default -> null;
                };
                if (path == null || channel.getSampler() == null
                        || channel.getSampler() < 0 || channel.getSampler() >= animation.getSamplers().size()) continue;
                var sampler = animation.getSamplers().get(channel.getSampler());
                if (sampler.getInput() == null || sampler.getOutput() == null) continue;
                float[] times = inputCache.computeIfAbsent(sampler.getInput(), index ->
                        floatValues(AccessorDatas.createFloat(model.getAccessorModels().get(index)), 1));
                if (times.length == 0) continue;
                AccessorFloatData output = AccessorDatas.createFloat(model.getAccessorModels().get(sampler.getOutput()));
                float[] values = floatValues(output, path.components());
                GltfAsset.Interpolation interpolation = switch (sampler.getInterpolation() == null
                        ? "LINEAR" : sampler.getInterpolation()) {
                    case "STEP" -> GltfAsset.Interpolation.STEP;
                    case "CUBICSPLINE" -> GltfAsset.Interpolation.CUBIC_SPLINE;
                    default -> GltfAsset.Interpolation.LINEAR;
                };
                int multiplier = interpolation == GltfAsset.Interpolation.CUBIC_SPLINE ? 3 : 1;
                if (output.getNumElements() != times.length * multiplier) {
                    throw new IllegalArgumentException("Invalid animation output count in " + name);
                }
                tracks.add(new GltfAsset.AnimationTrack(channel.getTarget().getNode(), path,
                        interpolation, times, values));
            }
            if (!tracks.isEmpty()) {
                float start = (float) tracks.stream()
                        .mapToDouble(track -> track.times()[0])
                        .min()
                        .orElse(0d);
                float end = (float)tracks.stream().mapToDouble(track -> track.times()[track.times().length - 1]).max().orElse(start);
                result.add(new GltfAsset.AnimationClip(name, tracks, start, end));
            }
        }
        return result;
    }

    private static float[] floatValues(AccessorFloatData data, int components) {
        float[] result = new float[data.getNumElements() * components];
        for (int i = 0; i < data.getNumElements(); i++) {
            for (int c = 0; c < components; c++) result[i * components + c] = data.get(i, c);
        }
        return result;
    }

    private static float[] floatElement(AccessorFloatData data, int element, int components) {
        float[] result = new float[components];
        for (int c = 0; c < components; c++) result[c] = data.get(element, c);
        return result;
    }

    private static int[] intValues(AccessorData data, int components) {
        int[] result = new int[data.getNumElements() * components];
        for (int i = 0; i < data.getNumElements(); i++) for (int c = 0; c < components; c++) {
            result[i * components + c] = switch (data) {
                case AccessorByteData bytes -> bytes.getInt(i, c);
                case AccessorShortData shorts -> shorts.getInt(i, c);
                case AccessorIntData ints -> ints.get(i, c);
                default -> throw new IllegalArgumentException("Unsupported integer accessor " + data.getClass());
            };
        }
        return result;
    }

    private static float[] numberValues(AccessorData data, boolean normalized, int components) {
        float[] result = new float[data.getNumElements() * components];
        for (int i = 0; i < data.getNumElements(); i++) for (int c = 0; c < components; c++) {
            float value = switch (data) {
                case AccessorByteData bytes -> bytes.getInt(i, c);
                case AccessorShortData shorts -> shorts.getInt(i, c);
                case AccessorIntData ints -> ints.get(i, c);
                case AccessorFloatData floats -> floats.get(i, c);
                default -> throw new IllegalArgumentException("Unsupported numeric accessor " + data.getClass());
            };
            if (normalized && data instanceof AccessorByteData bytes) value /= bytes.isUnsigned() ? 255f : 127f;
            if (normalized && data instanceof AccessorShortData shorts) value /= shorts.isUnsigned() ? 65535f : 32767f;
            result[i * components + c] = value;
        }
        return result;
    }

    private static void generateNormals(float[] positions, int[] indices, float[] normals) {
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int ia = indices[i], ib = indices[i + 1], ic = indices[i + 2];
            a.set(positions[ia * 3], positions[ia * 3 + 1], positions[ia * 3 + 2]);
            b.set(positions[ib * 3], positions[ib * 3 + 1], positions[ib * 3 + 2]);
            c.set(positions[ic * 3], positions[ic * 3 + 1], positions[ic * 3 + 2]);
            Vector3f face = b.sub(a, new Vector3f()).cross(c.sub(a, new Vector3f()));
            for (int vertex : new int[]{ia, ib, ic}) {
                normals[vertex * 3] += face.x;
                normals[vertex * 3 + 1] += face.y;
                normals[vertex * 3 + 2] += face.z;
            }
        }
        for (int i = 0; i < positions.length / 3; i++) {
            Vector3f normal = new Vector3f(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
            if (normal.lengthSquared() > 1e-12f) normal.normalize(); else normal.set(0f, 1f, 0f);
            normals[i * 3] = normal.x; normals[i * 3 + 1] = normal.y; normals[i * 3 + 2] = normal.z;
        }
    }
}
