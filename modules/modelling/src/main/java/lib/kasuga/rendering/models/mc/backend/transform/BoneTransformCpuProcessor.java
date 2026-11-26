package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.backend.KsgVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@Deprecated
public class BoneTransformCpuProcessor {

    private final SkeletonInstance skeleton;
    private final int vertexCount;
    private final ExecutorService executor;
    private final CpuVertexSkinning cpuSkinning;
    private final Mesh[] tangentMeshes;
    private final long skeletonVersion;
    private final Consumer<Mesh> tangentCalculator;

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
        this.executor = executor;
        this.vertexCount = vertices.size();
        skeletonVersion = skeleton.getVersion();
        this.tangentCalculator = tangentCalculator;

        Map<VertexFormatElement, Integer> offsets = Map.of(
                VertexFormatElement.POSITION, bufOffsets.get(VertexFormatElement.POSITION),
                VertexFormatElement.NORMAL, bufOffsets.get(VertexFormatElement.NORMAL),
                RenderState.TANGENT, bufOffsets.get(RenderState.TANGENT));
        this.cpuSkinning = new CpuVertexSkinning(buffer, vertexSize, offsets, skeleton.getSkeleton(), vertices, meshes, true);
        this.tangentMeshes = tangentMeshes;
    }

    public void updateForVersion() {
        long currentVersion = skeleton.getVersion();
        if (currentVersion == skeletonVersion) return;

        long collectStart = ModelProfiler.start();
        BitSet dirtyIndices = cpuSkinning.collectDirtyIndices(skeleton.getLastDirtyBones());
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
        boolean recalculateTangents = CpuVertexSkinning.recalculateDynamicTangents();
        HashSet<Mesh> dirtyMeshes = new HashSet<>();
        long skinningStart = ModelProfiler.start();
        int start = dirtyIndices.nextSetBit(0);
        while (start >= 0) {
            int end = dirtyIndices.nextClearBit(start);
            updateSkinningRange(start, end,
                    KsgVertexBuffer.MULTI_THREADED_SKINNING_THRESHOLD,
                    !recalculateTangents, false);
            cpuSkinning.addMeshes(start, end, dirtyMeshes);
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

        // The deprecated entry point clears packed tangents when it skips tangent skinning.
        CpuVertexSkinning.Tangents tangents = updateTangent ? CpuVertexSkinning.Tangents.SKIN : CpuVertexSkinning.Tangents.CLEAR;
        cpuSkinning.updateRange(skeleton, startInclusive, endExclusive, tangents, null);
    }

    private void updateAllSkinning() {
        boolean recalculateTangents = CpuVertexSkinning.recalculateDynamicTangents();
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
        cpuSkinning.captureBaseTangents();
    }
}
