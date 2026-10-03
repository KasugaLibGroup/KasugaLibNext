package lib.kasuga.rendering.models.uml.typo.gltf;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.awt.image.BufferedImage;
import java.util.List;

/** Immutable format-level result of parsing a glTF 2.0 JSON or GLB asset. */
public record GltfAsset(List<Primitive> primitives, List<Texture> textures,
                        List<Skin> skins, NodeHierarchy nodes,
                        List<AnimationClip> animations) {
    public GltfAsset {
        primitives = List.copyOf(primitives);
        textures = List.copyOf(textures);
        skins = List.copyOf(skins);
        animations = List.copyOf(animations);
    }

    public AnimationClip animation(String name) {
        return animations.stream().filter(clip -> clip.name().equals(name)).findFirst().orElse(null);
    }

    public record Primitive(String name, int nodeIndex, int skinIndex,
                            float[] positions, float[] normals, float[] texCoords,
                            int[] indices, int[] joints, float[] weights,
                            Matrix4f nodeWorld, Material material) {
        public Primitive {
            positions = positions.clone();
            normals = normals.clone();
            texCoords = texCoords.clone();
            indices = indices.clone();
            joints = joints.clone();
            weights = weights.clone();
            nodeWorld = new Matrix4f(nodeWorld);
        }

        public int vertexCount() { return positions.length / 3; }
        public boolean skinned() { return skinIndex >= 0 && joints.length != 0 && weights.length != 0; }
    }

    public record Material(int textureIndex, AlphaMode alphaMode, Vector4f baseColor,
                           float metallic, float roughness, float alphaCutoff,
                           boolean doubleSided) {
        public Material {
            baseColor = new Vector4f(baseColor);
        }
    }

    public enum AlphaMode { OPAQUE, MASK, BLEND }

    public record Texture(String name, BufferedImage image) {}

    public record Skin(int[] jointNodeIndices, String[] jointNames,
                       int[] parentJointIndices, Matrix4f[] inverseBindMatrices) {
        public Skin {
            jointNodeIndices = jointNodeIndices.clone();
            jointNames = jointNames.clone();
            parentJointIndices = parentJointIndices.clone();
            inverseBindMatrices = java.util.Arrays.stream(inverseBindMatrices)
                    .map(Matrix4f::new).toArray(Matrix4f[]::new);
        }
    }

    public record NodeHierarchy(String[] names, int[] parents, int[] evaluationOrder,
                                Vector3f[] translations, Quaternionf[] rotations,
                                Vector3f[] scales, Matrix4f[] bindWorlds) {
        public NodeHierarchy {
            names = names.clone();
            parents = parents.clone();
            evaluationOrder = evaluationOrder.clone();
            translations = java.util.Arrays.stream(translations).map(Vector3f::new).toArray(Vector3f[]::new);
            rotations = java.util.Arrays.stream(rotations).map(Quaternionf::new).toArray(Quaternionf[]::new);
            scales = java.util.Arrays.stream(scales).map(Vector3f::new).toArray(Vector3f[]::new);
            bindWorlds = java.util.Arrays.stream(bindWorlds).map(Matrix4f::new).toArray(Matrix4f[]::new);
        }

        public int size() { return names.length; }
    }

    public record AnimationClip(String name, List<AnimationTrack> tracks,
                                float startTime, float endTime) {
        public AnimationClip { tracks = List.copyOf(tracks); }
        public float duration() { return Math.max(0f, endTime - startTime); }
    }

    public record AnimationTrack(int nodeIndex, AnimationPath path,
                                 Interpolation interpolation,
                                 float[] times, float[] values) {
        public AnimationTrack {
            times = times.clone();
            values = values.clone();
        }
    }

    public enum AnimationPath {
        TRANSLATION(3), ROTATION(4), SCALE(3);
        private final int components;
        AnimationPath(int components) { this.components = components; }
        public int components() { return components; }
    }

    public enum Interpolation { STEP, LINEAR, CUBIC_SPLINE }
}
