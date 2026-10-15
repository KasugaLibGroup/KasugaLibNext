package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.backend.KsgVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lib.kasuga.structure.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@Deprecated
public class BoneTransformCpuProcessor {

    private final ByteBuffer buffer;
    private final int vertexSize;
    private final SkeletonInstance skeleton;
    private final int vertexCount;
    private final ExecutorService executor;

    private final Vertex[] skinningVertices;
    private final Mesh[] skinningMeshes;
    private final Mesh[] tangentMeshes;
    private final float[] basePositions;
    private final float[] baseNormals;
    private final float[] baseTangents;
    private final BoneBindingFunc[] bindingFuncs;
    private final int[] boneWeightOffsets;
    private final int[] boneWeightCounts;
    private final Bone[] skinningBones;
    private final Transform[] bindInverses;
    private final float[] skinningWeights;
    private final Map<Bone, int[]> skinningIndicesByBone;
    private final BitSet dirtyIndices;
    private final long skeletonVersion;

    private final Consumer<Mesh> tangentCalculator;

    private final int posOffset, normOffset, tangentOffset;

//    private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
//    private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;


    public BoneTransformCpuProcessor(ByteBuffer buffer,
                                     int vertexSize,
                                     ModelInstance model,
                                     ArrayList<Vertex> vertices,
                                     ArrayList<Mesh> meshes,
                                     Mesh[] tangentMeshes,
                                     ExecutorService executor,
                                     Map<VertexFormatElement, Integer> bufOffsets,
                                     Consumer<Mesh> tangentCalculator) {
        this.skeleton = model.getSkeletonInstance();
        this.buffer = buffer;
        this.executor = executor;
        this.vertexSize = vertexSize;
        this.vertexCount = vertices.size();
        skeletonVersion = skeleton.getVersion();
        this.tangentCalculator = tangentCalculator;

        posOffset = bufOffsets.get(VertexFormatElement.POSITION);
        normOffset = bufOffsets.get(VertexFormatElement.NORMAL);
        tangentOffset = bufOffsets.get(RenderState.TANGENT);

        this.skinningVertices = vertices.toArray(new Vertex[0]);
        this.skinningMeshes = meshes.toArray(new Mesh[0]);
        this.tangentMeshes = tangentMeshes;
        this.basePositions = new float[vertexCount * 3];
        this.baseNormals = new float[vertexCount * 3];
        this.baseTangents = new float[vertexCount * 4];
        this.bindingFuncs = new BoneBindingFunc[vertexCount];
        this.boneWeightOffsets = new int[vertexCount];
        this.boneWeightCounts = new int[vertexCount];

        ArrayList<Bone> bones = new ArrayList<>();
        ArrayList<Transform> bindInverses =  new ArrayList<>();
        ArrayList<Float> weights = new ArrayList<>();

        HashMap<Bone, ArrayList<Integer>> indicesByBone = new HashMap<>();
        Transform identity =  new Transform();

        for (int i = 0; i < vertexCount; i++) {
            Vertex vertex = vertices.get(i);
            Mesh mesh = meshes.get(i);
            int componentOffset = i * 3;
            Vector3f position = vertex.getPosition();
            Vector3f normal = vertex.getNormal(mesh);
            basePositions[componentOffset] = position.x;
            basePositions[componentOffset + 1] = position.y;
            basePositions[componentOffset + 2] = position.z;

            baseNormals[componentOffset] = normal.x;
            baseNormals[componentOffset + 1] = normal.y;
            baseNormals[componentOffset + 2] = normal.z;

            bindingFuncs[i] = vertex.getBinding().getFunc();
            boneWeightOffsets[i] = bones.size();

            Pair<Bone, Float>[] bWeights = vertex.getBinding().getWeights();
            boneWeightCounts[i] = bWeights.length;

            for (Pair<Bone, Float> w : bWeights) {
                Bone b = w.getFirst();
                bones.add(b);
                indicesByBone.computeIfAbsent(b, ignored -> new ArrayList<>()).add(i);
                Pair<Transform, Transform> bindTransforms = skeleton
                        .getSkeleton()
                        .getBoneTransforms()
                        .getOrDefault(b, null);
                Transform inverse = bindTransforms == null ? identity : bindTransforms.getSecond();
                bindInverses.add(inverse);
                weights.add(w.getSecond());
            }
        }
        this.skinningBones = bones.toArray(new Bone[0]);
        this.bindInverses = bindInverses.toArray(new Transform[0]);
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
        this.dirtyIndices = new BitSet(vertexCount);
    }

    public void updateForVersion() {
        long currentVersion = skeleton.getVersion();
        if (currentVersion == skeletonVersion) return;

        long collectStart = ModelProfiler.start();
        BitSet dirtyIndices = collectDirtySkinningIndices(vertexCount);
        int dirtyCount = dirtyIndices.cardinality();

        if (skeleton.isLastFullUpdate()) {
            long updateStart = ModelProfiler.start();
            updateAllSkinning();
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.full", updateStart,
                        "vertices=" + vertexCount + ", reason=skeletonFull");
            }
            return;
        }

        if (dirtyCount == 0) {
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.none", collectStart,
                        "vertices=" + vertexCount +
                                ", dirtyBones=" + skeleton.getLastDirtyBones().size());
            }
            return;
        }

        if (dirtyCount * 4 >= vertexCount * 3) {
            long updateStart = ModelProfiler.start();
            updateAllSkinning();
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.full", updateStart,
                        "vertices=" + vertexCount + ", dirty=" + dirtyCount + ", reason=threshold");
            }
            return;
        }

        long updateStart = ModelProfiler.start();
        boolean recalculateTangents = recalculateDynamicTangents();
        HashSet<Mesh> dirtyMeshes = new HashSet<>();
        long skinningStart = ModelProfiler.start();
        int start = dirtyIndices.nextSetBit(0);
        while (start >= 0) {
            int end = dirtyIndices.nextClearBit(start);
            updateSkinningRange(start, end,
                    KsgVertexBuffer.MULTI_THREADED_SKINNING_THRESHOLD,
                    !recalculateTangents, false);
            dirtyMeshes.addAll(Arrays.asList(skinningMeshes).subList(start, end));
            start = dirtyIndices.nextSetBit(end);
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.cpu.partial.vertices", skinningStart,
                    "dirty=" + dirtyCount);
        }

        if (recalculateTangents) {
            long tangentStart = ModelProfiler.start();
            for (Mesh mesh : dirtyMeshes) {
                tangentCalculator.accept(mesh);
            }
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.partial.tangents", tangentStart,
                        "dirtyMeshes=" + dirtyMeshes.size());
            }
        }
        long dirtyUploadStart = ModelProfiler.start();
        Set<Mesh> dirtyTangentMeshes = recalculateTangents ? dirtyMeshes : Collections.emptySet();
//        markStaticGpuDirty(dirtyIndices, dirtyTangentMeshes);
//        markIrisGpuDirty(dirtyIndices, dirtyTangentMeshes);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.cpu.partial.markUpload", dirtyUploadStart,
                    "dirtyMeshes=" + (recalculateTangents ? dirtyMeshes.size() : 0));
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.cpu.partial", updateStart,
                    "vertices=" + vertexCount +
                            ", dirty=" + dirtyCount +
                            ", dirtyBones=" + skeleton.getLastDirtyBones().size() +
                            ", dirtyMeshes=" + dirtyMeshes.size() +
                            ", tangents=" + (recalculateTangents ? "mesh" : "skinned"));
        }
    }

    private BitSet collectDirtySkinningIndices(int vertexCount) {
        Set<Bone> dirtyBones = skeleton.getLastDirtyBones();
        dirtyIndices.clear();
        if (dirtyBones == null || dirtyBones.isEmpty()) {
            return dirtyIndices;
        }
        for (Bone bone : dirtyBones) {
            int[] indices = skinningIndicesByBone.get(bone);
            if (indices == null) continue;
            for (int index : indices) {
                if (index >= 0 && index < vertexCount) {
                    dirtyIndices.set(index);
                }
            }
        }
        return dirtyIndices;
    }

    public void updateSkinningRange(int startInclusive, int endExclusive, int multiThreadedThreshold,
                                    boolean updateTangent, boolean isRecursive) {
        int len = endExclusive - startInclusive;

        if (!isRecursive && len > multiThreadedThreshold) {
            int taskCount = (int) Math.ceil((float) len / (float) multiThreadedThreshold);
            CompletableFuture[] futures = new CompletableFuture[taskCount];

            for (int i = 0; i < taskCount; i++) {
                int taskStart = startInclusive + i * multiThreadedThreshold;
                int taskEnd = Math.min(taskStart + multiThreadedThreshold, endExclusive);
                futures[i] = (CompletableFuture.runAsync(() -> {
                    this.updateSkinningRange(taskStart, taskEnd, multiThreadedThreshold, updateTangent, true);
                }, executor));
            }
            CompletableFuture.allOf(futures).join();
            return;
        }

        List<BoneContext> contexts = new ArrayList<>();
        Map<Bone, Transform> absTransforms = skeleton.getAbsoluteTransforms();

        Vector3f position =  new Vector3f();
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
                if (updateTangent) setBaseTangent(i, tangent);
            } else if (func == BoneBindingFunc.BDEF) {
                applyBdef(i, absTransforms,
                        position, normal, updateTangent ? tangent : null,
                        scratchPosition, scratchNormal, scratchTangent);
            } else {
                skeleton.collectBoneContexts(contexts, vertex);
                Vertex transformed = func.apply(vertex, contexts);
                position.set(transformed.getPosition());
                normal.set(transformed.getNormal(mesh));
                if (updateTangent) setBaseTangent(i, tangent);
            }

            putSkinnedVertex(i, position, normal, tangent);
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
            Transform bindInverse = bindInverses[index];
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

    private void putSkinnedVertex(int index, Vector3f position, Vector3f normal, @Nullable Vector4f tangent) {
        int pOffset = index * vertexSize + posOffset;
        buffer.putFloat(pOffset, position.x());
        buffer.putFloat(pOffset + 4, position.y());
        buffer.putFloat(pOffset + 8, position.z());

        int normalOffset = index * vertexSize + normOffset;
        if (normal.lengthSquared() > 0f) {
            normal.normalize();
        }
        buffer.put(normalOffset, (byte) ((int) (normal.x() * 127) & 0xFF));
        buffer.put(normalOffset + 1, (byte) ((int) (normal.y() * 127) & 0xFF));
        buffer.put(normalOffset + 2, (byte) ((int) (normal.z() * 127) & 0xFF));

        if (tangent != null) {
            float tangentLength = (float) Math.sqrt(tangent.x() * tangent.x() + tangent.y() * tangent.y() + tangent.z() * tangent.z());
            if (tangentLength > 0.0f && !Float.isNaN(tangentLength)) {
                tangent.x /= tangentLength;
                tangent.y /= tangentLength;
                tangent.z /= tangentLength;
            }
            int tOffset = index * vertexSize + tangentOffset;
            buffer.putFloat(tOffset, tangent.x());
            buffer.putFloat(tOffset + 4, tangent.y());
            buffer.putFloat(tOffset + 8, tangent.z());
            buffer.putFloat(tOffset + 12, tangent.w());
        }
    }

    private boolean recalculateDynamicTangents() {
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

    private void updateAllSkinning() {
        boolean recalculateTangents = recalculateDynamicTangents();
        updateSkinningRange(0, vertexCount,
                KsgVertexBuffer.MULTI_THREADED_SKINNING_THRESHOLD,
                !recalculateTangents, false);
        if (recalculateTangents) {
            for (Mesh mesh : tangentMeshes) {
                tangentCalculator.accept(mesh);
            }
        }
    }

    public void captureBaseTangents() {
        for (int i = 0; i < skinningVertices.length; i++) {
            int tangentOffset = this.tangentOffset + i * vertexSize;
            int componentOffset = i * 4;
            baseTangents[componentOffset] = buffer.getFloat(tangentOffset);
            baseTangents[componentOffset + 1] = buffer.getFloat(tangentOffset + 4);
            baseTangents[componentOffset + 2] = buffer.getFloat(tangentOffset + 8);
            baseTangents[componentOffset + 3] = buffer.getFloat(tangentOffset + 12);
        }
    }
}
