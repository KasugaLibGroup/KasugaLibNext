package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.structure.Pair;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Cached bind-pose data and CPU skinning for the packed model vertex layout. */
@ApiStatus.Internal
public final class CpuVertexSkinning {
    public enum Tangents { SKIN, KEEP, CLEAR }

    private final ByteBuffer buffer;
    private final int vertexSize;
    private final Map<VertexFormatElement, Integer> offsets;
    private final Vertex[] skinningVertices;
    private final Mesh[] skinningMeshes;
    private final float[] basePositions;
    private final float[] baseNormals;
    private final float[] baseTangents;
    private final BoneBindingFunc[] bindingFuncs;
    private final int[] boneWeightOffsets;
    private final int[] boneWeightCounts;
    private final Bone[] skinningBones;
    private final Transform[] skinningBindInverses;
    private final float[] skinningWeights;
    private final Map<Bone, int[]> skinningIndicesByBone;
    private final BitSet dirtySkinningIndices;

    public CpuVertexSkinning(ByteBuffer buffer, int vertexSize, Map<VertexFormatElement, Integer> offsets,
                             Skeleton skeleton, List<Vertex> vertices, List<Mesh> meshes,
                             boolean identityForMissingBindTransform) {
        this.buffer = buffer;
        this.vertexSize = vertexSize;
        this.offsets = offsets;
        this.skinningVertices = vertices.toArray(new Vertex[0]);
        this.skinningMeshes = meshes.toArray(new Mesh[0]);
        this.basePositions = new float[vertices.size() * 3];
        this.baseNormals = new float[vertices.size() * 3];
        this.baseTangents = new float[vertices.size() * 4];
        this.bindingFuncs = new BoneBindingFunc[vertices.size()];
        this.boneWeightOffsets = new int[vertices.size()];
        this.boneWeightCounts = new int[vertices.size()];
        ArrayList<Bone> bones = new ArrayList<>();
        ArrayList<Transform> bindInverses = new ArrayList<>();
        ArrayList<Float> weights = new ArrayList<>();
        HashMap<Bone, ArrayList<Integer>> indicesByBone = new HashMap<>();
        Transform missingBindInverse = identityForMissingBindTransform ? new Transform() : null;
        for (int i = 0; i < vertices.size(); i++) {
            Vertex vertex = vertices.get(i);
            Mesh mesh = meshes.get(i);
            int componentOffset = i * 3;
            Vector3f position = vertex.getPosition();
            Vector3f normal = vertex.getNormal(mesh);
            basePositions[componentOffset] = position.x();
            basePositions[componentOffset + 1] = position.y();
            basePositions[componentOffset + 2] = position.z();
            baseNormals[componentOffset] = normal.x();
            baseNormals[componentOffset + 1] = normal.y();
            baseNormals[componentOffset + 2] = normal.z();
            bindingFuncs[i] = vertex.getBinding().getFunc();
            boneWeightOffsets[i] = bones.size();
            Pair<Bone, Float>[] bindingWeights = vertex.getBinding().getWeights();
            boneWeightCounts[i] = bindingWeights.length;
            for (Pair<Bone, Float> pair : bindingWeights) {
                Bone bone = pair.getFirst();
                bones.add(bone);
                indicesByBone.computeIfAbsent(bone, ignored -> new ArrayList<>()).add(i);
                Pair<Transform, Transform> bindTransforms = skeleton.getBoneTransforms().get(bone);
                Transform bindInverse = bindTransforms == null ? missingBindInverse : bindTransforms.getSecond();
                bindInverses.add(bindInverse);
                weights.add(pair.getSecond());
            }
        }
        this.skinningBones = bones.toArray(new Bone[0]);
        this.skinningBindInverses = bindInverses.toArray(new Transform[0]);
        this.skinningWeights = new float[weights.size()];
        for (int i = 0; i < weights.size(); i++) {
            this.skinningWeights[i] = weights.get(i);
        }
        HashMap<Bone, int[]> compactIndicesByBone = new HashMap<>();
        for (Map.Entry<Bone, ArrayList<Integer>> entry : indicesByBone.entrySet()) {
            ArrayList<Integer> values = entry.getValue();
            int[] compactValues = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                compactValues[i] = values.get(i);
            }
            compactIndicesByBone.put(entry.getKey(), compactValues);
        }
        this.skinningIndicesByBone = compactIndicesByBone;
        this.dirtySkinningIndices = new BitSet(vertices.size());
    }

    public int vertexCount() {
        return skinningVertices.length;
    }

    public void addMeshes(int startInclusive, int endExclusive, Set<Mesh> destination) {
        destination.addAll(Arrays.asList(skinningMeshes).subList(startInclusive, endExclusive));
    }

    public void captureBaseTangents() {
        if (vertexCount() == 0) return;
        int tangentOffset = offsets.get(RenderState.TANGENT);
        for (int i = 0; i < skinningVertices.length; i++) {
            int offset = tangentOffset + i * vertexSize;
            int componentOffset = i * 4;
            baseTangents[componentOffset] = buffer.getFloat(offset);
            baseTangents[componentOffset + 1] = buffer.getFloat(offset + 4);
            baseTangents[componentOffset + 2] = buffer.getFloat(offset + 8);
            baseTangents[componentOffset + 3] = buffer.getFloat(offset + 12);
        }
    }

    public BitSet collectDirtyIndices(Set<Bone> dirtyBones) {
        dirtySkinningIndices.clear();
        if (dirtyBones == null || dirtyBones.isEmpty()) {
            return dirtySkinningIndices;
        }
        for (Bone bone : dirtyBones) {
            int[] indices = skinningIndicesByBone.get(bone);
            if (indices == null) continue;
            for (int index : indices) {
                if (index >= 0 && index < vertexCount()) {
                    dirtySkinningIndices.set(index);
                }
            }
        }
        return dirtySkinningIndices;
    }

    public static boolean recalculateDynamicTangents() {
        String env = System.getenv("KASUGA_PROFILE_MODEL_RECALCULATE_DYNAMIC_TANGENTS");
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env);
        }
        String property = System.getProperty("kasuga.profileModel.recalculateDynamicTangents");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        return false;
    }

    /** Writes one range. Scratch vectors belong to this call so ranges can run in parallel. */
    public void updateRange(SkeletonInstance skeleton, int startInclusive, int endExclusive, Tangents tangents,
                            @Nullable Consumer<Vector3f> includePosition) {
        if (startInclusive >= endExclusive) return;
        boolean updateTangents = tangents == Tangents.SKIN;
        int positionOffset = offsets.get(VertexFormatElement.POSITION);
        int normalOffset = offsets.get(VertexFormatElement.NORMAL);
        int tangentOffset = tangents == Tangents.KEEP ? 0 : offsets.get(RenderState.TANGENT);
        List<BoneContext> contexts = new ArrayList<>();
        Map<Bone, Transform> absoluteTransforms = skeleton.getAbsoluteTransforms();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        Vector4f tangent = new Vector4f();
        Vector3f scratchPosition = new Vector3f();
        Vector3f scratchNormal = new Vector3f();
        Vector3f scratchTangent = new Vector3f();
        for (int i = startInclusive; i < endExclusive; i++) {
            Vertex vertex = skinningVertices[i];
            Mesh mesh = skinningMeshes[i];
            BoneBindingFunc func = bindingFuncs[i];
            if (func == null || func == BoneBindingFunc.IDENTITY) {
                setBasePosition(i, position);
                setBaseNormal(i, normal);
                if (updateTangents) setBaseTangent(i, tangent);
            } else if (func == BoneBindingFunc.BDEF) {
                applyBdef(i, absoluteTransforms, position, normal, updateTangents ? tangent : null,
                        scratchPosition, scratchNormal, scratchTangent);
            } else {
                skeleton.collectBoneContexts(contexts, vertex);
                Vertex transformed = func.apply(vertex, contexts);
                position.set(transformed.getPosition());
                normal.set(transformed.getNormal(mesh));
                if (updateTangents) setBaseTangent(i, tangent);
            }
            putSkinnedVertex(i * vertexSize, positionOffset, normalOffset, tangentOffset,
                    position, normal, tangents == Tangents.KEEP ? null : tangent);
            if (includePosition != null) includePosition.accept(position);
        }
    }

    private void setBasePosition(int index, Vector3f position) {
        int componentOffset = index * 3;
        position.set(basePositions[componentOffset], basePositions[componentOffset + 1], basePositions[componentOffset + 2]);
    }

    private void setBaseNormal(int index, Vector3f normal) {
        int componentOffset = index * 3;
        normal.set(baseNormals[componentOffset], baseNormals[componentOffset + 1], baseNormals[componentOffset + 2]);
    }

    private void setBaseTangent(int index, Vector4f tangent) {
        int componentOffset = index * 4;
        tangent.set(
                baseTangents[componentOffset],
                baseTangents[componentOffset + 1],
                baseTangents[componentOffset + 2],
                baseTangents[componentOffset + 3]
        );
    }

    private void applyBdef(int skinningIndex, Map<Bone, Transform> absoluteTransforms, Vector3f position, Vector3f normal,
                           @Nullable Vector4f tangent, Vector3f scratchPosition, Vector3f scratchNormal,
                           Vector3f scratchTangent) {
        position.zero();
        normal.zero();
        if (tangent != null) {
            tangent.zero();
        }
        int weightOffset = boneWeightOffsets[skinningIndex];
        int weightCount = boneWeightCounts[skinningIndex];
        if (weightCount == 0) {
            setBasePosition(skinningIndex, position);
            setBaseNormal(skinningIndex, normal);
            if (tangent != null) setBaseTangent(skinningIndex, tangent);
            return;
        }
        int componentOffset = skinningIndex * 3;
        float baseX = basePositions[componentOffset];
        float baseY = basePositions[componentOffset + 1];
        float baseZ = basePositions[componentOffset + 2];
        float normalX = baseNormals[componentOffset];
        float normalY = baseNormals[componentOffset + 1];
        float normalZ = baseNormals[componentOffset + 2];
        int tangentOffset = skinningIndex * 4;
        float tangentX = tangent == null ? 0.0f : baseTangents[tangentOffset];
        float tangentY = tangent == null ? 0.0f : baseTangents[tangentOffset + 1];
        float tangentZ = tangent == null ? 0.0f : baseTangents[tangentOffset + 2];
        float tangentW = tangent == null ? 0.0f : baseTangents[tangentOffset + 3];
        for (int i = 0; i < weightCount; i++) {
            int index = weightOffset + i;
            Bone bone = skinningBones[index];
            float weight = skinningWeights[index];
            Transform absTransform = absoluteTransforms.get(bone);
            Transform bindInverse = skinningBindInverses[index];
            if (absTransform == null || bindInverse == null) continue;
            scratchPosition.set(baseX, baseY, baseZ);
            bindInverse.apply(scratchPosition);
            absTransform.apply(scratchPosition);
            position.add(scratchPosition.mul(weight));

            scratchNormal.set(normalX, normalY, normalZ);
            absTransform.normal().transform(scratchNormal);
            normal.add(scratchNormal.mul(weight));

            if (tangent != null) {
                scratchTangent.set(tangentX, tangentY, tangentZ);
                absTransform.normal().transform(scratchTangent);
                tangent.x += scratchTangent.x() * weight;
                tangent.y += scratchTangent.y() * weight;
                tangent.z += scratchTangent.z() * weight;
            }
        }
        if (tangent != null) {
            tangent.w = tangentW;
        }
    }

    private void putSkinnedVertex(int vertexOffset, int posOffset, int normalOffset, int tangentOffset,
                                  Vector3f position, Vector3f normal, @Nullable Vector4f tangent) {
        int pOffset = vertexOffset + posOffset;
        buffer.putFloat(pOffset, position.x());
        buffer.putFloat(pOffset + 4, position.y());
        buffer.putFloat(pOffset + 8, position.z());

        int nOffset = vertexOffset + normalOffset;
        if (normal.lengthSquared() > 0f) {
            normal.normalize();
        }
        buffer.put(nOffset, (byte) ((int) (normal.x() * 127) & 0xFF));
        buffer.put(nOffset + 1, (byte) ((int) (normal.y() * 127) & 0xFF));
        buffer.put(nOffset + 2, (byte) ((int) (normal.z() * 127) & 0xFF));

        if (tangent != null) {
            float tangentLength = (float) Math.sqrt(tangent.x() * tangent.x() + tangent.y() * tangent.y() + tangent.z() * tangent.z());
            if (tangentLength > 0.0f && !Float.isNaN(tangentLength)) {
                tangent.x /= tangentLength;
                tangent.y /= tangentLength;
                tangent.z /= tangentLength;
            }
            int tOffset = vertexOffset + tangentOffset;
            buffer.putFloat(tOffset, tangent.x());
            buffer.putFloat(tOffset + 4, tangent.y());
            buffer.putFloat(tOffset + 8, tangent.z());
            buffer.putFloat(tOffset + 12, tangent.w());
        }
    }
}
