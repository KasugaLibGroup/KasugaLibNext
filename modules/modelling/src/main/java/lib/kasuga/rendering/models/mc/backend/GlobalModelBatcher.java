package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaGlobalBatchShaderInstance;
import lib.kasuga.rendering.models.mc.backend.transform.BoneTransformTBO;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import lib.kasuga.rendering.models.mc.backend.vbuffer.FencedVertexBuffer;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lib.kasuga.rendering.models.uml.backend.FrameResourceCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

final class GlobalModelBatcher implements AutoCloseable {

    static final int MAX_OBJECTS_PER_BATCH = Short.MAX_VALUE;
    private static final int OBJECT_TEXELS = 9;
    private static final int FLOATS_PER_OBJECT = OBJECT_TEXELS * 4;
    private static final int FLOATS_PER_BONE = 36;
    private static final int MAX_MERGE_GAP = 64;

    private final Map<BatchKey, List<BatchItem>> submissions = new LinkedHashMap<>();
    private final FrameResourceCache<CacheKey, BatchBuffer> buffers = new FrameResourceCache<>(BatchBuffer::close);

    private Matrix4f modelViewMatrix;
    private Matrix4f projectionMatrix;
    private boolean collecting;
    private int textureBufferLimitTexels;

    void beginFrame() {
        buffers.beginFrame();
    }

    void endFrame() {
        buffers.endFrame();
    }

    void begin(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        RenderSystem.assertOnRenderThread();
        if (!buffers.isFrameOpen()) buffers.beginFrame();
        submissions.clear();
        this.modelViewMatrix = new Matrix4f(modelViewMatrix);
        this.projectionMatrix = new Matrix4f(projectionMatrix);
        if (textureBufferLimitTexels == 0) {
            textureBufferLimitTexels = GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        }
        collecting = true;
    }

    boolean isCollecting() {
        return collecting;
    }

    boolean submit(BackendInstance instance, ModelRenderPass pass, Matrix4f pose, Matrix3f normal,
                   float emissiveStrength, float ambientLightEnhancement) {
        if (!canBatch(instance, pass)) return false;
        BatchKey key = new BatchKey(pass, instance.getMeshMode(pass),
                Float.floatToIntBits(emissiveStrength),
                Float.floatToIntBits(ambientLightEnhancement));
        submissions.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new BatchItem(instance, new Matrix4f(pose), new Matrix3f(normal)));
        return true;
    }

    private boolean canBatch(BackendInstance instance, ModelRenderPass pass) {
        // Conventional source-over transparency must retain its sorted draw
        // boundaries; merging it into this VBO would make order unrecoverable.
        if (pass == ModelRenderPass.TRANSLUCENT) return false;
        if (!collecting || textureBufferLimitTexels < OBJECT_TEXELS) return false;
        return instance.hasPass(pass) && requiredBoneTexels(instance) <= textureBufferLimitTexels;
    }

    private static int requiredBoneTexels(BackendInstance instance) {
        BoneTransformTBO tbo = instance.getBoneTransformTBO();
        if (instance.usesCpuSkinning() || tbo == null) return 0;
        return Math.multiplyExact(tbo.getBoneCount(), FLOATS_PER_BONE / 4);
    }

    void flush() {
        if (!collecting) return;
        collecting = false;
        long profileStart = ModelProfiler.start();
        int objectCount = 0;
        int drawCount = 0;
        long submittedVertices = 0;
        try {
            for (Map.Entry<BatchKey, List<BatchItem>> entry : submissions.entrySet()) {
                List<BatchItem> items = entry.getValue();
                int chunk = 0;
                for (int from = 0; from < items.size();) {
                    int to = nextChunkEnd(items, from);
                    CacheKey cacheKey = new CacheKey(entry.getKey(), chunk++);
                    BatchBuffer buffer = buffers.acquire(cacheKey, BatchBuffer::new);
                    buffer.draw(items.subList(from, to), entry.getKey(), modelViewMatrix, projectionMatrix);
                    drawCount++;
                    objectCount += to - from;
                    for (int i = from; i < to; i++) {
                        FlatModelData data = items.get(i).instance().getData(entry.getKey().pass());
                        if (data != null) submittedVertices += data.getVertexCount();
                    }
                    from = to;
                }
            }
        } finally {
            submissions.clear();
            ModelProfiler.record("Rendering.globalBatch", profileStart,
                    "objects=" + objectCount + ", draws=" + drawCount
                            + ", vertices=" + submittedVertices + ", cachedBuffers=" + buffers.size());
        }
    }

    private int nextChunkEnd(List<BatchItem> items, int from) {
        return nextChunkEnd(items.size(), from, textureBufferLimitTexels,
                index -> requiredBoneTexels(items.get(index).instance()));
    }

    static int nextChunkEnd(int itemCount, int from, int textureLimitTexels,
                            IntUnaryOperator boneTexelsAt) {
        int maxObjects = Math.min(MAX_OBJECTS_PER_BATCH, textureLimitTexels / OBJECT_TEXELS);
        int to = from;
        long boneTexels = 0;
        while (to < itemCount && to - from < maxObjects) {
            int itemBoneTexels = boneTexelsAt.applyAsInt(to);
            if (itemBoneTexels < 0) {
                throw new IllegalArgumentException("Bone texture size cannot be negative");
            }
            if (itemBoneTexels > textureLimitTexels) break;
            if (to > from && boneTexels + itemBoneTexels > textureLimitTexels) break;
            boneTexels += itemBoneTexels;
            to++;
        }
        if (to == from) {
            throw new IllegalStateException("A global batch item exceeds the texture-buffer limit");
        }
        return to;
    }

    @Override
    public void close() {
        buffers.close();
        submissions.clear();
    }

    static record BatchKey(ModelRenderPass pass, VertexFormat.Mode mode,
                            int emissiveBits, int ambientLightEnhancementBits) {
        float emissiveStrength() {
            return Float.intBitsToFloat(emissiveBits);
        }

        float ambientLightEnhancement() {
            return Float.intBitsToFloat(ambientLightEnhancementBits);
        }
    }

    private record CacheKey(BatchKey key, int chunk) {}

    private record BatchItem(BackendInstance instance, Matrix4f pose, Matrix3f normal) {}

    private static final class BatchBuffer implements AutoCloseable {

        private final List<BackendInstance> layout = new ArrayList<>();
        private final List<Integer> vertexOffsets = new ArrayList<>();
        private final List<Integer> boneOffsets = new ArrayList<>();
        private final IdentityHashMap<BackendInstance, Long> boneVersions = new IdentityHashMap<>();

        private FencedVertexBuffer vertexBuffer;
        private ByteBuffer mergedVertices;
        private int vertexCount;

        private final TextureUploadRing instanceBuffers = new TextureUploadRing();
        private int instanceTextureId;
        private final TextureUploadRing boneBuffers = new TextureUploadRing();
        private int boneTextureId;
        private FloatBuffer instanceUpload;
        /** Reused render-thread staging storage; bone layouts usually stay stable across frames. */
        private FloatBuffer boneUpload;
        private final float[] matrix4 = new float[16];
        private final float[] matrix3 = new float[9];
        private final BitSet mergedDirty = new BitSet();
        private final Set<BackendInstance> clearedInstances =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        void draw(List<BatchItem> items, BatchKey key,
                  Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
            RenderSystem.assertOnRenderThread();
            this.entryPass = key.pass();
            for (BatchItem item : items) item.instance().prepareForGlobalBatch(key.pass());

            boolean rebuilt = !matchesLayout(items);
            if (rebuilt) rebuild(items, key.mode());
            else updateDirtyVertices(items);

            updateBones(items, rebuilt);
            updateInstances(items);
            drawBuffer(key, modelViewMatrix, projectionMatrix);
        }

        private boolean matchesLayout(List<BatchItem> items) {
            if (layout.size() != items.size()) return false;
            for (int i = 0; i < layout.size(); i++) {
                BackendInstance instance = items.get(i).instance();
                FlatModelData data = instance.getData(entryPass);
                if (layout.get(i) != instance || data == null || data.getVertexCount() <= 0) return false;
            }
            return true;
        }

        private ModelRenderPass entryPass;

        private void rebuild(List<BatchItem> items, VertexFormat.Mode mode) {
            layout.clear();
            vertexOffsets.clear();
            boneOffsets.clear();
            boneVersions.clear();
            vertexCount = 0;
            int boneCount = 0;
            for (BatchItem item : items) {
                BackendInstance instance = item.instance();
                layout.add(instance);
                vertexOffsets.add(vertexCount);
                boneOffsets.add(boneCount);
                vertexCount += instance.getData(entryPass).getVertexCount();
                BoneTransformTBO tbo = instance.getBoneTransformTBO();
                if (!instance.usesCpuSkinning() && tbo != null) boneCount += tbo.getBoneCount();
            }

            int vertexSize = RenderState.UML_VERTEX_FORMAT.getVertexSize();
            int byteSize = Math.multiplyExact(vertexCount, vertexSize);
            if (mergedVertices != null) MemoryUtil.memFree(mergedVertices);
            mergedVertices = MemoryUtil.memAlloc(byteSize).order(ByteOrder.nativeOrder());

            for (int i = 0; i < items.size(); i++) {
                copyWholeInstance(items.get(i).instance(), i, vertexOffsets.get(i));
            }
            uploadWholeVertexBuffer(mode, byteSize);
            clearDirtyVertices(items);
        }

        private void copyWholeInstance(BackendInstance instance, int objectIndex, int globalVertex) {
            FlatModelData data = instance.getData(entryPass);
            int vertexSize = data.getVertexSize();
            int byteCount = data.getVertexCount() * vertexSize;
            MemoryUtil.memCopy(MemoryUtil.memAddress(data.getBuffer()),
                    MemoryUtil.memAddress(mergedVertices) + (long) globalVertex * vertexSize,
                    byteCount);
            patchObjectIndices(globalVertex, data.getVertexCount(), data.getOverlayOffset(), vertexSize, objectIndex);
        }

        private void updateDirtyVertices(List<BatchItem> items) {
            mergedDirty.clear();
            for (int objectIndex = 0; objectIndex < items.size(); objectIndex++) {
                BackendInstance instance = items.get(objectIndex).instance();
                FlatModelData data = instance.getData(entryPass);
                BitSet dirty = data.getDirtyVertices();
                int globalOffset = vertexOffsets.get(objectIndex);
                for (int start = dirty.nextSetBit(0); start >= 0;) {
                    int end = dirty.nextClearBit(start);
                    int byteOffset = start * data.getVertexSize();
                    int byteCount = (end - start) * data.getVertexSize();
                    MemoryUtil.memCopy(MemoryUtil.memAddress(data.getBuffer()) + byteOffset,
                            MemoryUtil.memAddress(mergedVertices)
                                    + (long) (globalOffset + start) * data.getVertexSize(),
                            byteCount);
                    patchObjectIndices(globalOffset + start, end - start, data.getOverlayOffset(),
                            data.getVertexSize(), objectIndex);
                    mergedDirty.set(globalOffset + start, globalOffset + end);
                    start = dirty.nextSetBit(end);
                }
            }
            uploadDirtyVertexRanges(mergedDirty);
            clearDirtyVertices(items);
        }

        private void clearDirtyVertices(List<BatchItem> items) {
            clearedInstances.clear();
            for (BatchItem item : items) {
                if (clearedInstances.add(item.instance())) {
                    item.instance().clearBatchDirtyVertices(entryPass);
                }
            }
            clearedInstances.clear();
        }

        private void patchObjectIndices(int firstVertex, int count, int overlayOffset,
                                        int vertexSize, int objectIndex) {
            for (int vertex = firstVertex; vertex < firstVertex + count; vertex++) {
                int offset = vertex * vertexSize + overlayOffset;
                mergedVertices.putShort(offset, (short) objectIndex);
                mergedVertices.putShort(offset + Short.BYTES, (short) 0);
            }
        }

        private void uploadWholeVertexBuffer(VertexFormat.Mode mode, int byteSize) {
            if (vertexBuffer == null) vertexBuffer = new FencedVertexBuffer(RenderState.UML_VERTEX_FORMAT, mode, MAX_MERGE_GAP);
            vertexBuffer.upload(mergedVertices.duplicate().position(0).limit(byteSize), null, true);
        }

        private void uploadDirtyVertexRanges(BitSet dirty) {
            if (dirty.isEmpty() || vertexBuffer == null) return;
            vertexBuffer.upload(mergedVertices.duplicate().clear(), dirty, false);
        }

        private void updateBones(List<BatchItem> items, boolean force) {
            int totalBones = 0;
            boolean changed = force;
            for (BatchItem item : items) {
                BackendInstance instance = item.instance();
                BoneTransformTBO tbo = instance.getBoneTransformTBO();
                if (instance.usesCpuSkinning() || tbo == null) continue;
                totalBones += tbo.getBoneCount();
                Long previous = boneVersions.get(instance);
                changed |= previous == null || previous != tbo.getSkeletonVersion();
            }
            if (!changed) return;

            int requiredFloats = Math.max(1, totalBones * FLOATS_PER_BONE);
            if (boneUpload == null || boneUpload.capacity() < requiredFloats) {
                if (boneUpload != null) MemoryUtil.memFree(boneUpload);
                boneUpload = MemoryUtil.memAllocFloat(requiredFloats);
            }
            FloatBuffer merged = boneUpload;
            merged.clear();
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                BatchItem item = items.get(itemIndex);
                BackendInstance instance = item.instance();
                BoneTransformTBO tbo = instance.getBoneTransformTBO();
                if (instance.usesCpuSkinning() || tbo == null || tbo.getUploadCache() == null) continue;
                FloatBuffer source = tbo.getUploadCache().duplicate();
                source.position(0);
                source.limit(tbo.getBoneCount() * FLOATS_PER_BONE);
                merged.put(source);
            }
            merged.flip();
            boneTextureId = boneBuffers.upload(merged);
            for (BatchItem item : items) {
                BoneTransformTBO tbo = item.instance().getBoneTransformTBO();
                if (!item.instance().usesCpuSkinning() && tbo != null)
                    boneVersions.put(item.instance(), tbo.getSkeletonVersion());
            }
        }

        private void updateInstances(List<BatchItem> items) {
            int requiredFloats = items.size() * FLOATS_PER_OBJECT;
            if (instanceUpload == null || instanceUpload.capacity() < requiredFloats) {
                if (instanceUpload != null) MemoryUtil.memFree(instanceUpload);
                instanceUpload = MemoryUtil.memAllocFloat(requiredFloats);
            }
            FloatBuffer data = instanceUpload;
            data.clear();
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                BatchItem item = items.get(itemIndex);
                BackendInstance instance = item.instance();
                item.pose().get(matrix4);
                item.normal().get(matrix3);
                data.put(matrix4);
                for (int column = 0; column < 3; column++) {
                    int offset = column * 3;
                    data.put(matrix3[offset]).put(matrix3[offset + 1]).put(matrix3[offset + 2]).put(0f);
                }
                BoneTransformTBO tbo = instance.getBoneTransformTBO();
                boolean gpuSkinning = !instance.usesCpuSkinning() && tbo != null && tbo.isValid();
                FlatModelData modelData = instance.getData(entryPass);
                data.put(modelData.getBrightness())
                        .put(boneOffsets.get(itemIndex))
                        .put(gpuSkinning ? 1f : 0f)
                        .put(0f);
                int light = modelData.getLightmap();
                int overlay = modelData.getOverlay();
                data.put(light & 0xffff).put((light >>> 16) & 0xffff)
                        .put(overlay & 0xffff).put((overlay >>> 16) & 0xffff);
            }
            data.flip();
            instanceTextureId = instanceBuffers.upload(data);
        }

        private void drawBuffer(BatchKey key, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
            if (vertexBuffer == null || vertexCount == 0) return;
            RenderType renderType = RenderState.getGlobalBatchRenderType(key.pass());
            ShaderInstance shader = null;
            boolean rasterizerDiscard = GL11.glGetBoolean(GL30.GL_RASTERIZER_DISCARD);
            try {
                renderType.setupRenderState();
                shader = RenderSystem.getShader();
                if (!(shader instanceof KasugaGlobalBatchShaderInstance batchShader)) return;
                batchShader.setBatchTextures(instanceTextureId, boneTextureId);
                batchShader.setEmissiveStrength(key.emissiveStrength());
                batchShader.setAmbientLightEnhancement(key.ambientLightEnhancement());
                batchShader.setAlphaMode(key.pass().shaderAlphaMode());
                batchShader.setOitMode(0);
                shader.setDefaultUniforms(key.mode(), modelViewMatrix, projectionMatrix,
                        Minecraft.getInstance().getWindow());
                shader.apply();
                BufferUploader.reset();
                vertexBuffer.current().bind();
                if (rasterizerDiscard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                try { vertexBuffer.current().draw(); }
                finally {
                    vertexBuffer.markSubmitted();
                    instanceBuffers.markSubmitted();
                    boneBuffers.markSubmitted();
                }
            } finally {
                VertexBuffer.unbind();
                BufferUploader.reset();
                renderType.clearRenderState();
                if (rasterizerDiscard) GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                else GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                if (shader != null) shader.clear();
            }
        }

        @Override
        public void close() {
            if (vertexBuffer != null) vertexBuffer.close();
            if (mergedVertices != null) MemoryUtil.memFree(mergedVertices);
            if (instanceUpload != null) MemoryUtil.memFree(instanceUpload);
            if (boneUpload != null) MemoryUtil.memFree(boneUpload);
            instanceBuffers.close();
            boneBuffers.close();
            vertexBuffer = null;
            mergedVertices = null;
            instanceUpload = null;
            boneUpload = null;
            instanceTextureId = boneTextureId = 0;
        }
    }
}
