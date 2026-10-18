package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import lib.kasuga.mixins.client.AccessorBufferBuilder;
import lib.kasuga.mixins.client.AccessorByteBufferBuilder;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.uml.backend.VersionedBackendRenderable;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.TangentHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FastColor;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Deprecated
public class KsgVertexBuffer implements AutoCloseable, VersionedBackendRenderable {

    public static int MULTI_THREADED_SKINNING_THRESHOLD = 10000;
    private final ExecutorService executor;
    private final ByteBuffer buffer;
    private boolean building = false;
    private final int vertexSize, numVertices;
    private final HashMap<Vertex, HashMap<Mesh, Integer[]>> vertexMap;
    private final HashMap<VertexFormatElement, Integer> offsets;
    private final HashMap<VertexFormatElement, Integer> bufOffsets;
    private VertexFormatElement uv1_element, uv2_element;
    private float minX = Float.POSITIVE_INFINITY;
    private float minY = Float.POSITIVE_INFINITY;
    private float minZ = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY;
    private float maxY = Float.NEGATIVE_INFINITY;
    private float maxZ = Float.NEGATIVE_INFINITY;
    private Vertex[] skinningVertices = new Vertex[0];
    private Mesh[] skinningMeshes = new Mesh[0];
    private Mesh[] tangentMeshes = new Mesh[0];
    private float[] basePositions = new float[0];
    private float[] baseNormals = new float[0];
    private float[] baseTangents = new float[0];
    private BoneBindingFunc[] bindingFuncs = new BoneBindingFunc[0];
    private int[] boneWeightOffsets = new int[0];
    private int[] boneWeightCounts = new int[0];
    private Bone[] skinningBones = new Bone[0];
    private Transform[] skinningBindInverses = new Transform[0];
    private float[] skinningWeights = new float[0];
    private Map<Bone, int[]> skinningIndicesByBone = Map.of();
    private BitSet dirtySkinningIndices = new BitSet();
    private Bone[] gpuSkinningBones = new Bone[0];
    private Map<Bone, Integer> gpuSkinningBoneIndex = Map.of();
    private Map<Bone, Transform> gpuSkinningBindInverses = Map.of();
    private boolean gpuSkinningDataReady = false;
    private int gpuBoneTransformBufferId = 0;
    private int gpuBoneTransformTextureId = 0;
    private FloatBuffer gpuBoneTransformUploadCache;
    private ByteBuffer uploadCache;
    private boolean uploadCacheValid = false;
    private int uploadCacheVertexSize = -1;
    private int uploadCachePackedLight = -1;
    private int uploadCachePackedOverlay = -1;
    private boolean uploadCacheReadAlpha = true;
    private float uploadCacheBrightness = Float.NaN;
    private ByteBuffer irisStaticCache;
    private boolean irisStaticCacheValid = false;
    private int irisStaticCacheVertexSize = -1;
    private int irisStaticCachePackedLight = -1;
    private int irisStaticCachePackedOverlay = -1;
    private boolean irisStaticCacheReadAlpha = true;
    private float irisStaticCacheBrightness = Float.NaN;
    private VertexBuffer staticGpuBuffer;
    private boolean staticGpuBufferValid = false;
    private int staticGpuBufferVertexSize = -1;
    private int staticGpuBufferPackedLight = -1;
    private int staticGpuBufferPackedOverlay = -1;
    private boolean staticGpuBufferReadAlpha = true;
    private float staticGpuBufferBrightness = Float.NaN;
    private final BitSet staticGpuDirtyVertices = new BitSet();
    private ByteBuffer staticRangeUploadCache;
    private VertexBuffer irisGpuBuffer;
    private boolean irisGpuBufferValid = false;
    private int irisGpuBufferVertexSize = -1;
    private int irisGpuBufferPackedLight = -1;
    private int irisGpuBufferPackedOverlay = -1;
    private boolean irisGpuBufferReadAlpha = true;
    private float irisGpuBufferBrightness = Float.NaN;
    private final BitSet irisGpuDirtyVertices = new BitSet();
    private ByteBufferBuilder[] multiThreadedUploadCaches;
    private CompletableFuture<Void>[] irisSkinningFutures;
    private ByteBuffer irisRangeUploadCache;
    private int irisGpuSkinningProgramId = 0;
    private int irisGpuSkinningSourceBufferId = 0;
    private int irisGpuSkinningSourceVaoId = 0;
    private int irisGpuSkinningOutputBufferId = 0;
    private boolean irisGpuSkinningSourceValid = false;
    private boolean irisGpuSkinningLoggedMissingPositionAttribute = false;

    @Getter
    private boolean closed = false;

    @Getter
    private final Builder modifier;
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN);
    private static final int NEW_ENTITY_POSITION_OFFSET = getFormatOffset(DefaultVertexFormat.NEW_ENTITY, VertexFormatElement.POSITION);
    private static final int NEW_ENTITY_COLOR_OFFSET = getFormatOffset(DefaultVertexFormat.NEW_ENTITY, VertexFormatElement.COLOR);
    private static final int NEW_ENTITY_UV0_OFFSET = getFormatOffset(DefaultVertexFormat.NEW_ENTITY, VertexFormatElement.UV0);
    private static final int NEW_ENTITY_UV1_OFFSET = getFormatOffset(DefaultVertexFormat.NEW_ENTITY, VertexFormatElement.UV1);
    private static final int NEW_ENTITY_UV2_OFFSET = getFormatOffset(DefaultVertexFormat.NEW_ENTITY, VertexFormatElement.UV2);
    private static final int NEW_ENTITY_NORMAL_OFFSET = getFormatOffset(DefaultVertexFormat.NEW_ENTITY, VertexFormatElement.NORMAL);
    private static final int RANGE_UPLOAD_MAX_MERGE_GAP_VERTICES = 64;
    private static final int IRIS_GPU_SKINNING_OUTPUT_STRIDE = 12;
    private static final int IRIS_GPU_SKINNING_TEXTURE_UNIT = GL13.GL_TEXTURE7;

    private static final ResourceLocation IRIS_GPU_SKINNING_VERTEX_SHADER_LOCATION =
            ResourceLocation.parse("kasuga_lib:shaders/ksg_skinning.transform.glsl");

    public interface ElementUploader {
        void upload(BufferBuilder builder, long pointer, int vertexIndex,
                    VertexFormatElement element, ByteBuffer buffer,
                    PoseStack.Pose pose, float brightness,
                    int packedLight, int packedOverlay,
                    boolean readAlpha);
    }

    public KsgVertexBuffer(int numVertices, int vertexSize, VertexFormat format, Builder modifier, ExecutorService executor) {
        this.buffer = MemoryUtil.memCalloc(numVertices * vertexSize);
        buffer.order(ByteOrder.nativeOrder());
        this.building = true;
        this.vertexSize = vertexSize;
        this.numVertices = numVertices;
        this.vertexMap = new HashMap<>();
        offsets = new HashMap<>();
        bufOffsets = new HashMap<>();
        int o = 0, ob = 0;
        for (VertexFormatElement element : format.getElements()) {
            offsets.put(element, o);
            bufOffsets.put(element, ob);
            if (!element.equals(VertexFormatElement.UV1) && !element.equals(VertexFormatElement.UV2)) {
                if (element.equals(VertexFormatElement.COLOR)) ob += element.byteSize() * 2;
                else ob += element.byteSize();
            }
            o += element.byteSize();
        }
        this.modifier = modifier;
        uv1_element = null; uv2_element = null;
        findUv1AndUv2(format);
        this.executor = executor;
        this.irisSkinningFutures = null;
    }

    @Override
    public void close() throws Exception {
        MemoryUtil.memFree(buffer);
        if (uploadCache != null) {
            MemoryUtil.memFree(uploadCache);
            uploadCache = null;
        }
        if (irisStaticCache != null) {
            MemoryUtil.memFree(irisStaticCache);
            irisStaticCache = null;
        }
        if (staticGpuBuffer != null) {
            staticGpuBuffer.close();
            staticGpuBuffer = null;
        }
        if (staticRangeUploadCache != null) {
            MemoryUtil.memFree(staticRangeUploadCache);
            staticRangeUploadCache = null;
        }
        if (irisGpuBuffer != null) {
            irisGpuBuffer.close();
            irisGpuBuffer = null;
        }
        if (irisRangeUploadCache != null) {
            MemoryUtil.memFree(irisRangeUploadCache);
            irisRangeUploadCache = null;
        }
        if (irisGpuSkinningProgramId != 0) {
            GL20.glDeleteProgram(irisGpuSkinningProgramId);
            irisGpuSkinningProgramId = 0;
        }
        if (irisGpuSkinningSourceBufferId != 0) {
            GL15.glDeleteBuffers(irisGpuSkinningSourceBufferId);
            irisGpuSkinningSourceBufferId = 0;
        }
        if (irisGpuSkinningSourceVaoId != 0) {
            GL30.glDeleteVertexArrays(irisGpuSkinningSourceVaoId);
            irisGpuSkinningSourceVaoId = 0;
        }
        if (irisGpuSkinningOutputBufferId != 0) {
            GL15.glDeleteBuffers(irisGpuSkinningOutputBufferId);
            irisGpuSkinningOutputBufferId = 0;
        }
        if (gpuBoneTransformBufferId != 0) {
            GL15.glDeleteBuffers(gpuBoneTransformBufferId);
            gpuBoneTransformBufferId = 0;
        }
        if (gpuBoneTransformTextureId != 0) {
            GL11.glDeleteTextures(gpuBoneTransformTextureId);
            gpuBoneTransformTextureId = 0;
        }
        if (gpuBoneTransformUploadCache != null) {
            MemoryUtil.memFree(gpuBoneTransformUploadCache);
            gpuBoneTransformUploadCache = null;
        }
        if (multiThreadedUploadCaches != null) {
            for (ByteBufferBuilder bbb : multiThreadedUploadCaches) {
                MemoryUtil.nmemFree(((AccessorByteBufferBuilder) bbb).kasuga$getPointer());
            }
            multiThreadedUploadCaches = null;
        }
        closed = true;
    }

    public void checkClosed() {
        if (closed) throw new IllegalStateException("This buffer is already closed!");
    }

    public static boolean isGpuSkinningEnabled() {
        if (IrisCompat.isUsingShaderPack()) return false;
        return isGpuSkinningRequested();
    }

    public static boolean isIrisGpuSkinningEnabled() {
        return IrisCompat.isUsingShaderPack() && isGpuSkinningRequested();
    }

    private static boolean isGpuSkinningRequested() {
        String env = System.getenv("KASUGA_MODEL_GPU_SKINNING");
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env);
        }
        String property = System.getProperty("kasuga.modelGpuSkinning");
        return property != null && !property.isBlank() && Boolean.parseBoolean(property);
    }

    private void findUv1AndUv2(VertexFormat format) {
        for (VertexFormatElement element : format.getElements()) {
            if (!element.usage().equals(VertexFormatElement.Usage.UV)) continue;
            int index = element.index();
            if (index == 3) {
                uv1_element = element;
            } else if (index == 4) {
                uv2_element = element;
            }
        }
    }

    private long getPos(long pointer, VertexFormatElement element) {
        return pointer + offsets.get(element);
    }

    private int getBufPos(long index, VertexFormatElement element) {
        return (int) ((index * vertexSize) + bufOffsets.get(element));
    }

//    @Deprecated
//    public void uploadOnIrisPresent(BufferBuilder builder,
//                                    PoseStack.Pose pose,
//                                    float brightness,
//                                    int packedLight,
//                                    int packedOverlay,
//                                    boolean readAlpha) {
//        checkClosed();
//        AccessorBufferBuilder accessor = (AccessorBufferBuilder) builder;
//        ByteBufferBuilder dstBuffer = accessor.getBuffer();
//        int avs = accessor.getVertexSize();
//        if (!isIrisStaticCacheValid(avs, brightness, packedLight, packedOverlay, readAlpha)) {
//            ensureIrisStaticCache(avs);
//            irisStaticCache = fillIrisGpuCache(null, builder, brightness, packedLight, packedOverlay, readAlpha, 0, numVertices).build().byteBuffer();
//            irisStaticCacheVertexSize = avs;
//            irisStaticCacheBrightness = brightness;
//            irisStaticCachePackedLight = packedLight;
//            irisStaticCachePackedOverlay = packedOverlay;
//            irisStaticCacheReadAlpha = readAlpha;
//            irisStaticCacheValid = true;
//        }
//        long pointer = -1L;
//        long staticPointer = MemoryUtil.memAddress(irisStaticCache);
//        int srcPositionOffset = bufOffsets.get(VertexFormatElement.POSITION);
//        int srcNormalOffset = bufOffsets.get(VertexFormatElement.NORMAL);
//        org.joml.Matrix4f poseMatrix = pose.pose();
//        org.joml.Matrix3f normalMatrix = pose.normal();
//        for (int i = 0; i < numVertices; i++) {
//            long vertexPointer = pointer + (long) i * avs;
//            long cachedVertexPointer = staticPointer + (long) i * avs;
//            int vertexOffset = i * vertexSize;
//            MemoryUtil.memCopy(cachedVertexPointer + NEW_ENTITY_COLOR_OFFSET, vertexPointer + NEW_ENTITY_COLOR_OFFSET, 4L);
//            MemoryUtil.memCopy(cachedVertexPointer + NEW_ENTITY_UV0_OFFSET, vertexPointer + NEW_ENTITY_UV0_OFFSET, 8L);
//            MemoryUtil.memCopy(cachedVertexPointer + NEW_ENTITY_UV1_OFFSET, vertexPointer + NEW_ENTITY_UV1_OFFSET, 4L);
//            MemoryUtil.memCopy(cachedVertexPointer + NEW_ENTITY_UV2_OFFSET, vertexPointer + NEW_ENTITY_UV2_OFFSET, 4L);
//            int bufOffset = vertexOffset + srcPositionOffset;
//            float x = buffer.getFloat(bufOffset);
//            float y = buffer.getFloat(bufOffset + 4);
//            float z = buffer.getFloat(bufOffset + 8);
//            MemoryUtil.memPutFloat(vertexPointer + NEW_ENTITY_POSITION_OFFSET,
//                    poseMatrix.m00() * x + poseMatrix.m10() * y + poseMatrix.m20() * z + poseMatrix.m30());
//            MemoryUtil.memPutFloat(vertexPointer + NEW_ENTITY_POSITION_OFFSET + 4L,
//                    poseMatrix.m01() * x + poseMatrix.m11() * y + poseMatrix.m21() * z + poseMatrix.m31());
//            MemoryUtil.memPutFloat(vertexPointer + NEW_ENTITY_POSITION_OFFSET + 8L,
//                    poseMatrix.m02() * x + poseMatrix.m12() * y + poseMatrix.m22() * z + poseMatrix.m32());
//
//            bufOffset = vertexOffset + srcNormalOffset;
//            float nx = ((float) buffer.get(bufOffset)) / 127f;
//            float ny = ((float) buffer.get(bufOffset + 1)) / 127f;
//            float nz = ((float) buffer.get(bufOffset + 2)) / 127f;
//            float tx = normalMatrix.m00() * nx + normalMatrix.m10() * ny + normalMatrix.m20() * nz;
//            float ty = normalMatrix.m01() * nx + normalMatrix.m11() * ny + normalMatrix.m21() * nz;
//            float tz = normalMatrix.m02() * nx + normalMatrix.m12() * ny + normalMatrix.m22() * nz;
//            putNormal(vertexPointer + NEW_ENTITY_NORMAL_OFFSET, tx, ty, tz);
//        }
//        accessor.setVertices(accessor.getVertices() + numVertices);
//    }

//    private boolean isIrisStaticCacheValid(int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
//        return irisStaticCacheValid &&
//                irisStaticCache != null &&
//                irisStaticCacheVertexSize == vertexSize &&
//                irisStaticCachePackedLight == packedLight &&
//                irisStaticCachePackedOverlay == packedOverlay &&
//                irisStaticCacheReadAlpha == readAlpha &&
//                Float.compare(irisStaticCacheBrightness, brightness) == 0;
//    }
//
//    private void ensureIrisStaticCache(int vertexSize) {
//        int size = vertexSize * numVertices;
//        if (irisStaticCache != null && irisStaticCache.capacity() >= size) {
//            irisStaticCache.clear();
//            return;
//        }
//        if (irisStaticCache != null) {
//            MemoryUtil.memFree(irisStaticCache);
//        }
//        irisStaticCache = MemoryUtil.memAlloc(size);
//        irisStaticCache.order(ByteOrder.nativeOrder());
//    }

    private void invalidateIrisStaticCache() {
        irisStaticCacheValid = false;
    }

    private void invalidateStaticGpuBuffer() {
        staticGpuBufferValid = false;
        staticGpuDirtyVertices.clear();
    }

    private void invalidateIrisGpuBuffer() {
        irisGpuBufferValid = false;
        irisGpuDirtyVertices.clear();
    }

    private void invalidateIrisGpuSkinningSource() {
        irisGpuSkinningSourceValid = false;
    }

//    @Deprecated
//    private void fillIrisStaticCache(long pointer, int avs, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
//        int srcColorOffset = bufOffsets.get(VertexFormatElement.COLOR);
//        int srcUv0Offset = bufOffsets.get(VertexFormatElement.UV0);
//        float colorScale = brightness / 255f;
//        for (int i = 0; i < numVertices; i++) {
//            long vertexPointer = pointer + (long) i * avs;
//            int vertexOffset = i * vertexSize;
//            int bufOffset = vertexOffset + srcColorOffset;
//            int a = buffer.get(bufOffset) & 0xff;
//            int b = buffer.get(bufOffset + 1) & 0xff;
//            int g = buffer.get(bufOffset + 2) & 0xff;
//            int r = buffer.get(bufOffset + 3) & 0xff;
//            int ma = buffer.get(bufOffset + 4) & 0xff;
//            int mb = buffer.get(bufOffset + 5) & 0xff;
//            int mg = buffer.get(bufOffset + 6) & 0xff;
//            int mr = buffer.get(bufOffset + 7) & 0xff;
//
//            int af = readAlpha ? (a * ma) / 255 : ma;
//            int bf = (int) (b * mb * colorScale);
//            int gf = (int) (g * mg * colorScale);
//            int rf = (int) (r * mr * colorScale);
//            int colorFinal = af << 24 | bf << 16 | gf << 8 | rf;
//            MemoryUtil.memPutInt(vertexPointer + NEW_ENTITY_COLOR_OFFSET, IS_LITTLE_ENDIAN ?
//                    colorFinal :
//                    Integer.reverseBytes(colorFinal)
//            );
//
//            bufOffset = vertexOffset + srcUv0Offset;
//            MemoryUtil.memPutFloat(vertexPointer + NEW_ENTITY_UV0_OFFSET, buffer.getFloat(bufOffset));
//            MemoryUtil.memPutFloat(vertexPointer + NEW_ENTITY_UV0_OFFSET + 4L, buffer.getFloat(bufOffset + 4));
//            putPackedUV(vertexPointer + NEW_ENTITY_UV1_OFFSET, packedOverlay);
//            putPackedUV(vertexPointer + NEW_ENTITY_UV2_OFFSET, packedLight);
//        }
//    }

    private ByteBufferBuilder fillIrisGpuCache(@Nullable ByteBufferBuilder byteBufferBuilder,
                                               BufferBuilder builder,
                                               float brightness, int packedLight, int packedOverlay, boolean readAlpha, int startIndex, int numVertices) {
        int srcPositionOffset = bufOffsets.get(VertexFormatElement.POSITION);
        int srcColorOffset = bufOffsets.get(VertexFormatElement.COLOR);
        int srcUv0Offset = bufOffsets.get(VertexFormatElement.UV0);
        int srcNormalOffset = bufOffsets.get(VertexFormatElement.NORMAL);
        float colorScale = brightness / 255f;

        ByteBufferBuilder bbb;
        if (byteBufferBuilder == null) {
            int vertexSize = ((AccessorBufferBuilder) builder).getVertexFormat().getVertexSize();
            bbb = new ByteBufferBuilder(numVertices * vertexSize);
        } else {
            bbb = byteBufferBuilder;
            ((AccessorByteBufferBuilder) bbb).kasuga$setWriteOffset(0);
        }
        BufferBuilder bufferBuilder = new BufferBuilder(bbb, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);

        int bufOffset;
        for (int i = startIndex; i < startIndex + numVertices; i++) {
            int vertexOffset = i * this.vertexSize;

            bufOffset = vertexOffset + srcColorOffset;
            int a = buffer.get(bufOffset) & 0xff;
            int b = buffer.get(bufOffset + 1) & 0xff;
            int g = buffer.get(bufOffset + 2) & 0xff;
            int r = buffer.get(bufOffset + 3) & 0xff;
            int ma = buffer.get(bufOffset + 4) & 0xff;
            int mb = buffer.get(bufOffset + 5) & 0xff;
            int mg = buffer.get(bufOffset + 6) & 0xff;
            int mr = buffer.get(bufOffset + 7) & 0xff;

            int af = readAlpha ? (a * ma) / 255 : ma;
            int bf = (int) (b * mb * colorScale);
            int gf = (int) (g * mg * colorScale);
            int rf = (int) (r * mr * colorScale);
            int colorFinal = af << 24 | bf << 16 | gf << 8 | rf;

            bufOffset = vertexOffset + srcPositionOffset;
            float x = buffer.getFloat(bufOffset);
            float y = buffer.getFloat(bufOffset + 4);
            float z = buffer.getFloat(bufOffset + 8);

            bufOffset = vertexOffset + srcNormalOffset;
            float nx = ((float) buffer.get(bufOffset)) / 127f;
            float ny = ((float) buffer.get(bufOffset + 1)) / 127f;
            float nz = ((float) buffer.get(bufOffset + 2)) / 127f;

            bufOffset = vertexOffset + srcUv0Offset;
            float u = buffer.getFloat(bufOffset);
            float v = buffer.getFloat(bufOffset + 4);

            bufferBuilder.addVertex(x, y, z, colorFinal, u, v, packedOverlay, packedLight, nx, ny, nz);
        }
        return bbb;
    }

//    @Deprecated
//    private void fillIrisGpuCache(long pointer, int avs, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
//        int srcPositionOffset = bufOffsets.get(VertexFormatElement.POSITION);
//        int srcColorOffset = bufOffsets.get(VertexFormatElement.COLOR);
//        int srcUv0Offset = bufOffsets.get(VertexFormatElement.UV0);
//        int srcNormalOffset = bufOffsets.get(VertexFormatElement.NORMAL);
//        long bufferPointer = MemoryUtil.memAddress(buffer);
//        float colorScale = brightness / 255f;
//        for (int i = 0; i < numVertices; i++) {
//            long vertexPointer = pointer + (long) i * avs;
//            int vertexOffset = i * vertexSize;
//            long sourcePointer = bufferPointer + vertexOffset;
//            MemoryUtil.memCopy(sourcePointer + srcPositionOffset, vertexPointer + NEW_ENTITY_POSITION_OFFSET, 12L);
//
//            int bufOffset = vertexOffset + srcColorOffset;
//            int a = buffer.get(bufOffset) & 0xff;
//            int b = buffer.get(bufOffset + 1) & 0xff;
//            int g = buffer.get(bufOffset + 2) & 0xff;
//            int r = buffer.get(bufOffset + 3) & 0xff;
//            int ma = buffer.get(bufOffset + 4) & 0xff;
//            int mb = buffer.get(bufOffset + 5) & 0xff;
//            int mg = buffer.get(bufOffset + 6) & 0xff;
//            int mr = buffer.get(bufOffset + 7) & 0xff;
//
//            int af = readAlpha ? (a * ma) / 255 : ma;
//            int bf = (int) (b * mb * colorScale);
//            int gf = (int) (g * mg * colorScale);
//            int rf = (int) (r * mr * colorScale);
//            int colorFinal = af << 24 | bf << 16 | gf << 8 | rf;
//            MemoryUtil.memPutInt(vertexPointer + NEW_ENTITY_COLOR_OFFSET, IS_LITTLE_ENDIAN ?
//                    colorFinal :
//                    Integer.reverseBytes(colorFinal)
//            );
//
//            MemoryUtil.memCopy(sourcePointer + srcUv0Offset, vertexPointer + NEW_ENTITY_UV0_OFFSET, 8L);
//            putPackedUV(vertexPointer + NEW_ENTITY_UV1_OFFSET, packedOverlay);
//            putPackedUV(vertexPointer + NEW_ENTITY_UV2_OFFSET, packedLight);
//            MemoryUtil.memCopy(sourcePointer + srcNormalOffset, vertexPointer + NEW_ENTITY_NORMAL_OFFSET, 3L);
//        }
//    }

//    @Deprecated
//    public void upload(BufferBuilder builder,
//                       PoseStack.Pose pose,
//                       @Nullable KasugaShaderInstance shader,
//                       float brightness, float emissiveStrength,
//                       int packedLight, int packedOverlay,
//                       boolean readAlpha) {
//        if (IrisCompat.isUsingShaderPack()) {
//            uploadOnIrisPresent(builder, pose, brightness, packedLight, packedOverlay, readAlpha);
//            return;
//        }
//        checkClosed();
//        Objects.requireNonNull(shader);
//        shader.setCurrentPose(pose);
//        shader.setEmissiveStrength(emissiveStrength);
//        shader.setLightData(brightness, packedLight, packedOverlay);
//        shader.setGpuSkinningState(gpuSkinningDataReady && isGpuSkinningEnabled(), gpuBoneTransformTextureId);
//        shader.apply();
//        AccessorBufferBuilder accessor = (AccessorBufferBuilder) builder;
//        ByteBufferBuilder buf = accessor.getBuffer();
//        int avs = accessor.getVertexSize();
//        long pointer = buf.reserve(avs * numVertices);
//        if (isUploadCacheValid(avs, brightness, packedLight, packedOverlay, readAlpha)) {
//            MemoryUtil.memCopy(MemoryUtil.memAddress(uploadCache), pointer, (long) avs * numVertices);
//            accessor.setVertices(accessor.getVertices() + numVertices);
//            return;
//        }
//        ensureUploadCache(avs);
//        long cachePointer = MemoryUtil.memAddress(uploadCache);
//        fillUploadCache(cachePointer, avs, brightness, packedLight, packedOverlay, readAlpha);
//        MemoryUtil.memCopy(cachePointer, pointer, (long) avs * numVertices);
//        uploadCacheVertexSize = avs;
//        uploadCacheBrightness = brightness;
//        uploadCachePackedLight = packedLight;
//        uploadCachePackedOverlay = packedOverlay;
//        uploadCacheReadAlpha = readAlpha;
//        uploadCacheValid = true;
//        accessor.setVertices(accessor.getVertices() + numVertices);
//    }

//    private boolean isUploadCacheValid(int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
//        return uploadCacheValid &&
//                uploadCache != null &&
//                uploadCacheVertexSize == vertexSize &&
//                uploadCachePackedLight == packedLight &&
//                uploadCachePackedOverlay == packedOverlay &&
//                uploadCacheReadAlpha == readAlpha &&
//                Float.compare(uploadCacheBrightness, brightness) == 0;
//    }
//
//    private void ensureUploadCache(int vertexSize) {
//        int size = vertexSize * numVertices;
//        if (uploadCache != null && uploadCache.capacity() >= size) {
//            uploadCache.clear();
//            return;
//        }
//        if (uploadCache != null) {
//            MemoryUtil.memFree(uploadCache);
//        }
//        uploadCache = MemoryUtil.memAlloc(size);
//        uploadCache.order(ByteOrder.nativeOrder());
//    }

    private void invalidateUploadCache() {
        invalidateCpuUploadCaches();
        invalidateStaticGpuBuffer();
        invalidateIrisGpuBuffer();
        invalidateIrisGpuSkinningSource();
    }

    private void invalidateCpuUploadCaches() {
        uploadCacheValid = false;
        invalidateIrisStaticCache();
    }

    private void fillUploadCache(long pointer, int avs, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        fillUploadCacheRange(pointer, avs, brightness, packedLight, packedOverlay, readAlpha, 0, numVertices);
    }

    private void fillUploadCacheRange(long pointer, int avs, float brightness, int packedLight, int packedOverlay, boolean readAlpha,
                                      int startInclusive, int endExclusive) {
        int dstPositionOffset = offsets.get(VertexFormatElement.POSITION);
        int dstColorOffset = offsets.get(VertexFormatElement.COLOR);
        int dstUv0Offset = offsets.get(VertexFormatElement.UV0);
        int dstTangentOffset = offsets.get(RenderState.TANGENT);
        int dstNormalOffset = offsets.get(VertexFormatElement.NORMAL);
        int dstUv1LightOffset = offsets.get(VertexFormatElement.UV1);
        int dstUv2LightOffset = offsets.get(VertexFormatElement.UV2);

        int srcPositionOffset = bufOffsets.get(VertexFormatElement.POSITION);
        int srcColorOffset = bufOffsets.get(VertexFormatElement.COLOR);
        int srcUv0Offset = bufOffsets.get(VertexFormatElement.UV0);
        int srcTextureUvOffset = bufOffsets.get(RenderState.TEXTURE_UV);
        int srcTextureBoundsOffset = bufOffsets.get(RenderState.TEXTURE_BOUNDS);
        int dstTextureUvOffset = offsets.get(RenderState.TEXTURE_UV);
        int dstTextureBoundsOffset = offsets.get(RenderState.TEXTURE_BOUNDS);
        int srcTangentOffset = bufOffsets.get(RenderState.TANGENT);
        int srcNormalOffset = bufOffsets.get(VertexFormatElement.NORMAL);

        int srcBoneBindingTypeOffset = bufOffsets.get(RenderState.BONE_BINDING_TYPE);
        int dstBoneBindingTypeOffset = offsets.get(RenderState.BONE_BINDING_TYPE);

        int srcBoneIndicesOffset = bufOffsets.get(RenderState.BONE_INDICES);
        int srcBoneWeightsOffset = bufOffsets.get(RenderState.BONE_WEIGHTS);
        int dstBoneIndicesOffset = offsets.get(RenderState.BONE_INDICES);
        int dstBoneWeightsOffset = offsets.get(RenderState.BONE_WEIGHTS);

        int srcSDEFDataOffset = bufOffsets.get(RenderState.SDEF_R0);
        int dstSDEFDataOffset = offsets.get(RenderState.SDEF_R0);

        int srcUv1Offset = uv1_element == null ? -1 : bufOffsets.get(uv1_element);
        int srcUv2Offset = uv2_element == null ? -1 : bufOffsets.get(uv2_element);
        int dstUv1Offset = uv1_element == null ? -1 : offsets.get(uv1_element);
        int dstUv2Offset = uv2_element == null ? -1 : offsets.get(uv2_element);
        long bufferPointer = MemoryUtil.memAddress(buffer);
        for (int i = startInclusive; i < endExclusive; i++) {
            long vertexPointer = pointer + (long) (i - startInclusive) * avs;
            int vertexOffset = i * vertexSize;
            long sourcePointer = bufferPointer + vertexOffset;
            MemoryUtil.memCopy(sourcePointer + srcPositionOffset, vertexPointer + dstPositionOffset, 12L);

            long offset = vertexPointer + dstColorOffset;
            int bufOffset = vertexOffset + srcColorOffset;

            int a = buffer.get(bufOffset) & 0xff;
            int b = buffer.get(bufOffset + 1) & 0xff;
            int g = buffer.get(bufOffset + 2) & 0xff;
            int r = buffer.get(bufOffset + 3) & 0xff;
            int ma = buffer.get(bufOffset + 4) & 0xff;
            int mb = buffer.get(bufOffset + 5) & 0xff;
            int mg = buffer.get(bufOffset + 6) & 0xff;
            int mr = buffer.get(bufOffset + 7) & 0xff;

            int af = readAlpha ? (a * ma) / 255 : ma;
            int bf = (b * mb) / 255;
            int gf = (g * mg) / 255;
            int rf = (r * mr) / 255;
            int colorFinal = af << 24 | bf << 16 | gf << 8 | rf;
            MemoryUtil.memPutInt(offset, IS_LITTLE_ENDIAN ?
                    colorFinal :
                    Integer.reverseBytes(colorFinal)
            );

            MemoryUtil.memCopy(sourcePointer + srcUv0Offset, vertexPointer + dstUv0Offset, 8L);
            MemoryUtil.memCopy(sourcePointer + srcTextureUvOffset, vertexPointer + dstTextureUvOffset, 8L);
            MemoryUtil.memCopy(sourcePointer + srcTextureBoundsOffset, vertexPointer + dstTextureBoundsOffset, 16L);
            MemoryUtil.memCopy(sourcePointer + srcTangentOffset, vertexPointer + dstTangentOffset, 16L);
            MemoryUtil.memCopy(sourcePointer + srcNormalOffset, vertexPointer + dstNormalOffset, 3L);

            offset = vertexPointer + dstUv1LightOffset;
            putPackedUV(offset, packedOverlay);

            offset = vertexPointer + dstUv2LightOffset;
            putPackedUV(offset, packedLight);

            if (uv1_element != null) {
                MemoryUtil.memCopy(sourcePointer + srcUv1Offset, vertexPointer + dstUv1Offset, 8L);
            }
            if (uv2_element != null) {
                MemoryUtil.memCopy(sourcePointer + srcUv2Offset, vertexPointer + dstUv2Offset, 8L);
            }

            MemoryUtil.memCopy(sourcePointer + srcBoneBindingTypeOffset, vertexPointer + dstBoneBindingTypeOffset, 4L);
            MemoryUtil.memCopy(sourcePointer + srcBoneIndicesOffset, vertexPointer + dstBoneIndicesOffset, 16L);
            MemoryUtil.memCopy(sourcePointer + srcBoneWeightsOffset, vertexPointer + dstBoneWeightsOffset, 16L);
            MemoryUtil.memCopy(sourcePointer + srcSDEFDataOffset, vertexPointer + dstSDEFDataOffset, 36L);
        }
    }

    public void drawStatic(RenderType renderType,
                           PoseStack.Pose pose,
                           org.joml.Matrix4f modelViewMatrix,
                           org.joml.Matrix4f projectionMatrix,
                           KasugaShaderInstance shader,
                           float brightness, float emissiveStrength,
                           int packedLight, int packedOverlay,
                           boolean readAlpha) {
        checkClosed();
        Objects.requireNonNull(shader);
        int gpuVertexSize = RenderState.UML_VERTEX_FORMAT.getVertexSize();
        boolean layoutValid = isStaticGpuBufferLayoutValid(gpuVertexSize, readAlpha);
        String cacheState = "hit";
        int dirtyVertices = staticGpuDirtyVertices.cardinality();
        if (!layoutValid || dirtyVertices * 4 >= numVertices * 3) {
            uploadStaticGpuBuffer(gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
            cacheState = "miss";
        } else {
            boolean lightUpdated = updateStaticLightData(gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
            if (dirtyVertices > 0) {
                uploadStaticGpuRanges(gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
                cacheState = lightUpdated ? "light+range" : "range";
            } else if (lightUpdated) {
                cacheState = "light";
            }
        }
        long drawStart = ModelProfiler.start();
        shader.setCurrentPose(pose);
        shader.setEmissiveStrength(emissiveStrength);
        shader.setLightData(brightness, packedLight, packedOverlay);
        shader.setGpuSkinningState(gpuSkinningDataReady && isGpuSkinningEnabled(), gpuBoneTransformTextureId);
        renderType.setupRenderState();

        VertexBuffer bufferToUse = staticGpuBuffer;
        try {
            BufferUploader.reset();
            bufferToUse.bind();
            bufferToUse.drawWithShader(modelViewMatrix, projectionMatrix, shader);
        } finally {
            VertexBuffer.unbind();
            BufferUploader.reset();
            renderType.clearRenderState();
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("render.drawStatic", drawStart,
                    "cache=" + cacheState + ", vertices=" + numVertices);
        }
    }

    public void drawStaticOnIrisPresent(BufferBuilder builder, RenderType renderType,
                                        PoseStack.Pose pose,
                                        org.joml.Matrix4f modelViewMatrix,
                                        org.joml.Matrix4f projectionMatrix,
                                        float brightness,
                                        int packedLight,
                                        int packedOverlay,
                                        boolean readAlpha) {
        checkClosed();
        int gpuVertexSize = ((AccessorBufferBuilder) builder).getVertexFormat().getVertexSize();
        if (isIrisGpuSkinningEnabled() && gpuSkinningDataReady) {
            drawIrisGpuSkinned(builder, renderType, pose, modelViewMatrix, projectionMatrix,
                    gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
            return;
        }
        boolean layoutValid = isIrisGpuBufferValid(gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
        String cacheState = "hit";
        int dirtyVertices = irisGpuDirtyVertices.cardinality();
        if (!layoutValid || dirtyVertices * 4 >= numVertices * 3) {
            uploadIrisGpuBuffer(builder, gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
            cacheState = "miss";
        } else if (dirtyVertices > 0) {
            uploadIrisGpuRanges(builder, gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
            cacheState = "range";
        }
        long drawStart = ModelProfiler.start();
        renderType.setupRenderState();
        try {
            ShaderInstance shader = RenderSystem.getShader();
            BufferUploader.reset();
            irisGpuBuffer.bind();
            irisGpuBuffer.drawWithShader(new org.joml.Matrix4f(modelViewMatrix).mul(pose.pose()), projectionMatrix, shader);
        } finally {
            VertexBuffer.unbind();
            BufferUploader.reset();
            renderType.clearRenderState();
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("render.drawStatic.iris", drawStart,
                    "cache=" + cacheState + ", vertices=" + numVertices);
        }
    }

    private boolean isStaticGpuBufferLayoutValid(int vertexSize, boolean readAlpha) {
        return staticGpuBufferValid &&
                staticGpuBuffer != null &&
                staticGpuBufferVertexSize == vertexSize &&
                staticGpuBufferReadAlpha == readAlpha;
    }

    private boolean isStaticGpuBufferLightingValid(float brightness, int packedLight, int packedOverlay) {
        return staticGpuBufferValid &&
                staticGpuBuffer != null &&
                staticGpuBufferPackedLight == packedLight &&
                staticGpuBufferPackedOverlay == packedOverlay &&
                Float.compare(staticGpuBufferBrightness, brightness) == 0;
    }

    private boolean isIrisGpuBufferValid(int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        return irisGpuBufferValid &&
                irisGpuBuffer != null &&
                irisGpuBufferVertexSize == vertexSize &&
                irisGpuBufferPackedLight == packedLight &&
                irisGpuBufferPackedOverlay == packedOverlay &&
                irisGpuBufferReadAlpha == readAlpha &&
                Float.compare(irisGpuBufferBrightness, brightness) == 0;
    }

    private boolean isIrisGpuBufferLayoutValid(int vertexSize, boolean readAlpha) {
        return irisGpuBufferValid &&
                irisGpuBuffer != null &&
                irisGpuBufferVertexSize == vertexSize &&
                irisGpuBufferReadAlpha == readAlpha;
    }

    private void uploadStaticGpuBuffer(int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        long uploadStart = ModelProfiler.start();
        int size = vertexSize * numVertices;
        ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder(size);
        try {
            long pointer = byteBufferBuilder.reserve(size);
            fillUploadCache(pointer, vertexSize, brightness, packedLight, packedOverlay, readAlpha);
            ByteBufferBuilder.Result result = Objects.requireNonNull(byteBufferBuilder.build());
            MeshData meshData = new MeshData(result, new MeshData.DrawState(
                    RenderState.UML_VERTEX_FORMAT,
                    numVertices,
                    VertexFormat.Mode.QUADS.indexCount(numVertices),
                    VertexFormat.Mode.QUADS,
                    VertexFormat.IndexType.least(numVertices)
            ));
            if (staticGpuBuffer == null) {
                staticGpuBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            staticGpuBuffer.bind();
            try {
                staticGpuBuffer.upload(meshData);
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            byteBufferBuilder.close();
        }
        staticGpuBufferVertexSize = vertexSize;
        staticGpuBufferBrightness = brightness;
        staticGpuBufferPackedLight = packedLight;
        staticGpuBufferPackedOverlay = packedOverlay;
        staticGpuBufferReadAlpha = readAlpha;
        staticGpuBufferValid = true;
        staticGpuDirtyVertices.clear();
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("gpu.uploadStatic.full", uploadStart,
                    "bytes=" + size + ", vertices=" + numVertices);
        }
    }

    public boolean updateStaticLightData(float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        checkClosed();
        int gpuVertexSize = RenderState.UML_VERTEX_FORMAT.getVertexSize();
        return updateStaticLightData(gpuVertexSize, brightness, packedLight, packedOverlay, readAlpha);
    }

    public boolean updateStaticLightData(float brightness, int packedLight, int packedOverlay) {
        return updateStaticLightData(brightness, packedLight, packedOverlay, true);
    }

    private boolean updateStaticLightData(int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        if (!isStaticGpuBufferLayoutValid(vertexSize, readAlpha) ||
                isStaticGpuBufferLightingValid(brightness, packedLight, packedOverlay)) {
            return false;
        }
        long uploadStart = ModelProfiler.start();
        staticGpuBufferBrightness = brightness;
        staticGpuBufferPackedLight = packedLight;
        staticGpuBufferPackedOverlay = packedOverlay;
        staticGpuBufferReadAlpha = readAlpha;
        staticGpuBufferValid = true;
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("gpu.uploadStatic.light", uploadStart,
                    "bytes=0" +
                            ", vertices=" + numVertices +
                            ", mode=uniform");
        }
        return true;
    }

    private void uploadStaticGpuRanges(int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        int dirtyVertices = staticGpuDirtyVertices.cardinality();
        if (dirtyVertices * 4 >= numVertices * 3) {
            uploadStaticGpuBuffer(vertexSize, brightness, packedLight, packedOverlay, readAlpha);
            return;
        }
        long uploadStart = ModelProfiler.start();
        RenderSystem.assertOnRenderThread();
        BufferUploader.reset();
        int previousBinding = GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int ranges = 0;
        int uploadedBytes = 0;
        try {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, ((AccessorVertexBuffer) staticGpuBuffer).getVertexBufferId());
            int start = staticGpuDirtyVertices.nextSetBit(0);
            while (start >= 0) {
                int end = staticGpuDirtyVertices.nextClearBit(start);
                int next = staticGpuDirtyVertices.nextSetBit(end);
                while (next >= 0 && next - end <= RANGE_UPLOAD_MAX_MERGE_GAP_VERTICES) {
                    end = staticGpuDirtyVertices.nextClearBit(next);
                    next = staticGpuDirtyVertices.nextSetBit(end);
                }
                end = Math.min(end, numVertices);
                int byteCount = (end - start) * vertexSize;
                ensureStaticRangeUploadCache(byteCount);
                staticRangeUploadCache.clear();
                fillUploadCacheRange(MemoryUtil.memAddress(staticRangeUploadCache), vertexSize,
                        brightness, packedLight, packedOverlay, readAlpha, start, end);
                staticRangeUploadCache.limit(byteCount);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) start * vertexSize, staticRangeUploadCache);
                uploadedBytes += byteCount;
                ranges++;
                start = next;
            }
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBinding);
        }
        staticGpuDirtyVertices.clear();
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("gpu.uploadStatic.range", uploadStart,
                    "bytes=" + uploadedBytes +
                            ", vertices=" + dirtyVertices +
                            ", ranges=" + ranges);
        }
    }

    private void ensureStaticRangeUploadCache(int byteCount) {
        if (staticRangeUploadCache != null && staticRangeUploadCache.capacity() >= byteCount) {
            return;
        }
        if (staticRangeUploadCache != null) {
            MemoryUtil.memFree(staticRangeUploadCache);
        }
        staticRangeUploadCache = MemoryUtil.memAlloc(byteCount);
        staticRangeUploadCache.order(ByteOrder.nativeOrder());
    }

    private void uploadIrisGpuRanges(BufferBuilder builder, int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        if (vertexSize != DefaultVertexFormat.NEW_ENTITY.getVertexSize()) {
            uploadIrisGpuBuffer(builder, vertexSize, brightness, packedLight, packedOverlay, readAlpha);
            return;
        }
        int dirtyVertices = irisGpuDirtyVertices.cardinality();
        if (dirtyVertices * 4 >= numVertices * 3) {
            uploadIrisGpuBuffer(builder, vertexSize, brightness, packedLight, packedOverlay, readAlpha);
            return;
        }
        long uploadStart = ModelProfiler.start();
        RenderSystem.assertOnRenderThread();
        BufferUploader.reset();
        int previousBinding = GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int ranges = 0;
        int uploadedBytes = 0;
        try {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, ((AccessorVertexBuffer) irisGpuBuffer).getVertexBufferId());
            int start = irisGpuDirtyVertices.nextSetBit(0);
            while (start >= 0) {
                int end = irisGpuDirtyVertices.nextClearBit(start);
                int next = irisGpuDirtyVertices.nextSetBit(end);
                while (next >= 0 && next - end <= RANGE_UPLOAD_MAX_MERGE_GAP_VERTICES) {
                    end = irisGpuDirtyVertices.nextClearBit(next);
                    next = irisGpuDirtyVertices.nextSetBit(end);
                }
                end = Math.min(end, numVertices);
                int byteCount = (end - start) * vertexSize;
                ensureIrisRangeUploadCache(byteCount);
                irisRangeUploadCache.clear();
                fillIrisGpuCacheRange(MemoryUtil.memAddress(irisRangeUploadCache), vertexSize,
                        brightness, packedLight, packedOverlay, readAlpha, start, end);
                irisRangeUploadCache.limit(byteCount);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) start * vertexSize, irisRangeUploadCache);
                uploadedBytes += byteCount;
                ranges++;
                start = next;
            }
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBinding);
        }
        irisGpuDirtyVertices.clear();
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("gpu.uploadStatic.iris.range", uploadStart,
                    "bytes=" + uploadedBytes +
                            ", vertices=" + dirtyVertices +
                            ", ranges=" + ranges);
        }
    }

    private void ensureIrisRangeUploadCache(int byteCount) {
        if (irisRangeUploadCache != null && irisRangeUploadCache.capacity() >= byteCount) {
            return;
        }
        if (irisRangeUploadCache != null) {
            MemoryUtil.memFree(irisRangeUploadCache);
        }
        irisRangeUploadCache = MemoryUtil.memAlloc(byteCount);
        irisRangeUploadCache.order(ByteOrder.nativeOrder());
    }

    private void fillIrisGpuCacheRange(long pointer, int vertexSize, float brightness, int packedLight, int packedOverlay,
                                       boolean readAlpha, int startInclusive, int endExclusive) {
        int srcPositionOffset = bufOffsets.get(VertexFormatElement.POSITION);
        int srcColorOffset = bufOffsets.get(VertexFormatElement.COLOR);
        int srcUv0Offset = bufOffsets.get(VertexFormatElement.UV0);
        int srcNormalOffset = bufOffsets.get(VertexFormatElement.NORMAL);
        long bufferPointer = MemoryUtil.memAddress(buffer);
        float colorScale = brightness / 255f;
        for (int i = startInclusive; i < endExclusive; i++) {
            long vertexPointer = pointer + (long) (i - startInclusive) * vertexSize;
            int vertexOffset = i * this.vertexSize;
            long sourcePointer = bufferPointer + vertexOffset;
            MemoryUtil.memCopy(sourcePointer + srcPositionOffset, vertexPointer + NEW_ENTITY_POSITION_OFFSET, 12L);

            int bufOffset = vertexOffset + srcColorOffset;
            int a = buffer.get(bufOffset) & 0xff;
            int b = buffer.get(bufOffset + 1) & 0xff;
            int g = buffer.get(bufOffset + 2) & 0xff;
            int r = buffer.get(bufOffset + 3) & 0xff;
            int ma = buffer.get(bufOffset + 4) & 0xff;
            int mb = buffer.get(bufOffset + 5) & 0xff;
            int mg = buffer.get(bufOffset + 6) & 0xff;
            int mr = buffer.get(bufOffset + 7) & 0xff;

            int af = readAlpha ? (a * ma) / 255 : ma;
            int bf = (int) (b * mb * colorScale);
            int gf = (int) (g * mg * colorScale);
            int rf = (int) (r * mr * colorScale);
            int colorFinal = af << 24 | bf << 16 | gf << 8 | rf;
            MemoryUtil.memPutInt(vertexPointer + NEW_ENTITY_COLOR_OFFSET, IS_LITTLE_ENDIAN ?
                    colorFinal :
                    Integer.reverseBytes(colorFinal)
            );

            MemoryUtil.memCopy(sourcePointer + srcUv0Offset, vertexPointer + NEW_ENTITY_UV0_OFFSET, 8L);
            putPackedUV(vertexPointer + NEW_ENTITY_UV1_OFFSET, packedOverlay);
            putPackedUV(vertexPointer + NEW_ENTITY_UV2_OFFSET, packedLight);
            MemoryUtil.memCopy(sourcePointer + srcNormalOffset, vertexPointer + NEW_ENTITY_NORMAL_OFFSET, 3L);
            MemoryUtil.memPutByte(vertexPointer + NEW_ENTITY_NORMAL_OFFSET + 3L, (byte) 0);
        }
    }

    private void drawIrisGpuSkinned(BufferBuilder builder,
                                    RenderType renderType,
                                    PoseStack.Pose pose,
                                    org.joml.Matrix4f modelViewMatrix,
                                    org.joml.Matrix4f projectionMatrix,
                                    int vertexSize,
                                    float brightness,
                                    int packedLight,
                                    int packedOverlay,
                                    boolean readAlpha) {
        boolean layoutValid = isIrisGpuBufferLayoutValid(vertexSize, readAlpha);
        String cacheState = "gpu";
        if (!layoutValid) {
            uploadIrisGpuBuffer(builder, vertexSize, brightness, packedLight, packedOverlay, readAlpha);
            cacheState = "gpu+miss";
        } else if (!isIrisGpuBufferLightingValid(brightness, packedLight, packedOverlay)) {
            uploadIrisGpuBuffer(builder, vertexSize, brightness, packedLight, packedOverlay, readAlpha);
            cacheState = "gpu+light";
        }
        long skinningStart = ModelProfiler.start();
        dispatchIrisGpuSkinning();
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.gpu.iris.transformFeedback", skinningStart,
                    "vertices=" + numVertices + ", bones=" + gpuSkinningBones.length);
        }
        long drawStart = ModelProfiler.start();
        renderType.setupRenderState();
        int overriddenPositionLocation = -1;
        try {
            ShaderInstance shader = RenderSystem.getShader();
            Objects.requireNonNull(shader);
            Matrix4f irisModelViewMatrix = new Matrix4f(modelViewMatrix).mul(pose.pose());
            BufferUploader.reset();
            irisGpuBuffer.bind();
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, irisModelViewMatrix, projectionMatrix,
                    Minecraft.getInstance().getWindow());
            shader.apply();
            overriddenPositionLocation = overrideIrisGpuSkinnedPositionAttribute(shader);
            irisGpuBuffer.draw();
            shader.clear();
        } finally {
            restoreIrisStaticAttributes(overriddenPositionLocation);
            VertexBuffer.unbind();
            BufferUploader.reset();
            renderType.clearRenderState();
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("render.drawStatic.iris", drawStart,
                    "cache=" + cacheState + ", vertices=" + numVertices);
        }
    }

    private boolean isIrisGpuBufferLightingValid(float brightness, int packedLight, int packedOverlay) {
        return irisGpuBufferValid &&
                irisGpuBuffer != null &&
                irisGpuBufferPackedLight == packedLight &&
                irisGpuBufferPackedOverlay == packedOverlay &&
                Float.compare(irisGpuBufferBrightness, brightness) == 0;
    }

    private void dispatchIrisGpuSkinning() {
        RenderSystem.assertOnRenderThread();
        ensureIrisGpuSkinningObjects();
        uploadIrisGpuSkinningSourceIfNeeded();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(IRIS_GPU_SKINNING_TEXTURE_UNIT);
        int previousTextureBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        boolean rasterizerDiscard = GL11.glGetBoolean(GL30.GL_RASTERIZER_DISCARD);
        try {
            GL20.glUseProgram(irisGpuSkinningProgramId);
            int samplerLocation = GL20.glGetUniformLocation(irisGpuSkinningProgramId, "ksg_BoneTransforms");
            if (samplerLocation >= 0) {
                GL20.glUniform1i(samplerLocation, IRIS_GPU_SKINNING_TEXTURE_UNIT - GL13.GL_TEXTURE0);
            }
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpuBoneTransformTextureId);
            GL30.glBindVertexArray(irisGpuSkinningSourceVaoId);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, irisGpuSkinningOutputBufferId);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBeginTransformFeedback(GL11.GL_POINTS);
            GL11.glDrawArrays(GL11.GL_POINTS, 0, numVertices);
            GL30.glEndTransformFeedback();
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        } finally {
            if (rasterizerDiscard) {
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            } else {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            }
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
            GL30.glBindVertexArray(0);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTextureBinding);
            RenderSystem.activeTexture(previousActiveTexture);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL20.glUseProgram(previousProgram);
        }
    }

    private void ensureIrisGpuSkinningObjects() {
        if (irisGpuSkinningProgramId == 0) {
            irisGpuSkinningProgramId = createIrisGpuSkinningProgram();
        }
        if (irisGpuSkinningSourceBufferId == 0) {
            irisGpuSkinningSourceBufferId = GL15.glGenBuffers();
        }
        if (irisGpuSkinningSourceVaoId == 0) {
            irisGpuSkinningSourceVaoId = GL30.glGenVertexArrays();
        }
        if (irisGpuSkinningOutputBufferId == 0) {
            irisGpuSkinningOutputBufferId = GL15.glGenBuffers();
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, irisGpuSkinningOutputBufferId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) numVertices * IRIS_GPU_SKINNING_OUTPUT_STRIDE, GL15.GL_DYNAMIC_DRAW);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    private void uploadIrisGpuSkinningSourceIfNeeded() {
        if (irisGpuSkinningSourceValid) {
            return;
        }
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30.glBindVertexArray(irisGpuSkinningSourceVaoId);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, irisGpuSkinningSourceBufferId);
            ByteBuffer source = buffer.duplicate();
            source.clear();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, source, GL15.GL_STATIC_DRAW);
            setupIrisGpuSkinningSourceAttributes();
        } finally {
            GL30.glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        }
        irisGpuSkinningSourceValid = true;
    }

    private void setupIrisGpuSkinningSourceAttributes() {
        setupFloatAttribute(0, 3, bufOffsets.get(VertexFormatElement.POSITION));
        setupByteNormalAttribute(5, bufOffsets.get(VertexFormatElement.NORMAL));
        setupFloatAttribute(7, 4, bufOffsets.get(RenderState.TANGENT));
        setupIntAsFloatAttribute(8, 1, bufOffsets.get(RenderState.BONE_BINDING_TYPE));
        setupIntAsFloatAttribute(9, 4, bufOffsets.get(RenderState.BONE_INDICES));
        setupFloatAttribute(10, 4, bufOffsets.get(RenderState.BONE_WEIGHTS));
        setupFloatAttribute(11, 3, bufOffsets.get(RenderState.SDEF_R0));
        setupFloatAttribute(12, 3, bufOffsets.get(RenderState.SDEF_R1));
        setupFloatAttribute(13, 3, bufOffsets.get(RenderState.SDEF_C));
    }

    private void setupFloatAttribute(int index, int size, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, vertexSize, (long) offset);
    }

    private void setupByteNormalAttribute(int index, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, 3, GL11.GL_BYTE, true, vertexSize, (long) offset);
    }

    private void setupIntAsFloatAttribute(int index, int size, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_INT, false, vertexSize, (long) offset);
    }

    private int overrideIrisGpuSkinnedPositionAttribute(ShaderInstance shader) {
        int positionLocation = getFirstAttributeLocation(shader,
                "iris_Position",
                "Position",
                "vaPosition",
                "a_Position");
        if (positionLocation < 0) {
            positionLocation = 0;
            if (!irisGpuSkinningLoggedMissingPositionAttribute) {
                irisGpuSkinningLoggedMissingPositionAttribute = true;
                long attributeStart = ModelProfiler.start();
                ModelProfiler.record("skinning.gpu.iris.positionAttribute", attributeStart,
                        "missing=true, fallback=0");
            }
        }
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, irisGpuSkinningOutputBufferId);
        GL20.glEnableVertexAttribArray(positionLocation);
        GL20.glVertexAttribPointer(positionLocation, 3, GL11.GL_FLOAT, false, IRIS_GPU_SKINNING_OUTPUT_STRIDE, 0L);
        return positionLocation;
    }

    private int getFirstAttributeLocation(ShaderInstance shader, String... names) {
        int programId = shader.getId();
        for (String name : names) {
            int location = GL20.glGetAttribLocation(programId, name);
            if (location >= 0) {
                return location;
            }
        }
        return -1;
    }

    private void restoreIrisStaticAttributes(int overriddenPositionLocation) {
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, ((AccessorVertexBuffer) irisGpuBuffer).getVertexBufferId());
        DefaultVertexFormat.NEW_ENTITY.setupBufferState();
        if (overriddenPositionLocation > 0) {
            GL20.glDisableVertexAttribArray(overriddenPositionLocation);
        }
    }

    private String loadSkinningShaderSource() {
        Optional<Resource> resourceOpt = Minecraft
                .getInstance()
                .getResourceManager()
                .getResource(IRIS_GPU_SKINNING_VERTEX_SHADER_LOCATION);
        if (resourceOpt.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[1024];
        try (Reader reader = resourceOpt.get().openAsReader()) {
            int len;
            while ((len = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, len);
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private int createIrisGpuSkinningProgram() {
        String shaderSource = loadSkinningShaderSource();
        if (shaderSource.isBlank()) {
            throw new IllegalStateException("Failed to load Iris GPU skinning shader source from " + IRIS_GPU_SKINNING_VERTEX_SHADER_LOCATION);
        }
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, shaderSource);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 5, "Normal");
        GL20.glBindAttribLocation(program, 7, "Tangent");
        GL20.glBindAttribLocation(program, 8, "BoneBindingType");
        GL20.glBindAttribLocation(program, 9, "BoneIndices");
        GL20.glBindAttribLocation(program, 10, "BoneWeights");
        GL20.glBindAttribLocation(program, 11, "sdefR0");
        GL20.glBindAttribLocation(program, 12, "sdefR1");
        GL20.glBindAttribLocation(program, 13, "sdefC");
        GL30.glTransformFeedbackVaryings(program, new CharSequence[]{"tf_Position"}, GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(program);
        int linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS);
        GL20.glDetachShader(program, vertexShader);
        GL20.glDeleteShader(vertexShader);
        if (linked == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link Iris GPU skinning program: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        int compiled = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        if (compiled == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile Iris GPU skinning shader: " + log);
        }
        return shader;
    }

    private void uploadIrisGpuBuffer(BufferBuilder builder, int vertexSize, float brightness, int packedLight, int packedOverlay, boolean readAlpha) {
        long uploadStart = ModelProfiler.start();
        ByteBufferBuilder byteBufferBuilder = null;
        try {
            if (numVertices < MULTI_THREADED_SKINNING_THRESHOLD) {
                byteBufferBuilder = fillIrisGpuCache(null, builder, brightness, packedLight, packedOverlay, readAlpha, 0, numVertices);
            } else {
                int taskCount = Math.ceilDiv(numVertices, MULTI_THREADED_SKINNING_THRESHOLD);
                if (multiThreadedUploadCaches == null || multiThreadedUploadCaches.length != taskCount) {
                    if (multiThreadedUploadCaches != null) {
                        for (ByteBufferBuilder bbb : multiThreadedUploadCaches) {
                            bbb.close();
                        }
                    }
                    multiThreadedUploadCaches = new ByteBufferBuilder[taskCount];
                    for (int i = 0; i < taskCount; i++) {
                        multiThreadedUploadCaches[i] = new ByteBufferBuilder(MULTI_THREADED_SKINNING_THRESHOLD * vertexSize);
                    }
                }
                if (irisSkinningFutures == null || irisSkinningFutures.length != taskCount) {
                    irisSkinningFutures = new CompletableFuture[taskCount];
                }
                for (int i = 0; i < taskCount; i++) {
                    final int index = i;
                    final int taskStart = i * MULTI_THREADED_SKINNING_THRESHOLD;
                    final int taskEnd = Math.min(taskStart + MULTI_THREADED_SKINNING_THRESHOLD, numVertices);
                    irisSkinningFutures[i] = (CompletableFuture.runAsync(() -> {
                        fillIrisGpuCache(multiThreadedUploadCaches[index], builder, brightness, packedLight, packedOverlay, readAlpha, taskStart, taskEnd - taskStart);
                    }, executor));
                }
                byteBufferBuilder = new ByteBufferBuilder(numVertices * vertexSize);
                long pointer = ((AccessorByteBufferBuilder) byteBufferBuilder).kasuga$getPointer();
                CompletableFuture.allOf(irisSkinningFutures).join();

                for (int i = 0; i < taskCount; i++) {
                    ByteBufferBuilder bbb = multiThreadedUploadCaches[i];
                    int taskStart = i * MULTI_THREADED_SKINNING_THRESHOLD;
                    int taskVertices = Math.min(MULTI_THREADED_SKINNING_THRESHOLD, numVertices - taskStart);
                    int byteCount = taskVertices * vertexSize;
                    long innerPointer = ((AccessorByteBufferBuilder) bbb).kasuga$getPointer();
                    MemoryUtil.memCopy(innerPointer, pointer, byteCount);
                    pointer += byteCount;
                }
                ((AccessorByteBufferBuilder) byteBufferBuilder).kasuga$setWriteOffset(numVertices * vertexSize);
            }
            ByteBufferBuilder.Result result = Objects.requireNonNull(byteBufferBuilder.build());
            MeshData meshData = new MeshData(result, new MeshData.DrawState(
                    DefaultVertexFormat.NEW_ENTITY,
                    numVertices,
                    VertexFormat.Mode.QUADS.indexCount(numVertices),
                    VertexFormat.Mode.QUADS,
                    VertexFormat.IndexType.least(numVertices)
            ));
            if (irisGpuBuffer == null) {
                irisGpuBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            irisGpuBuffer.bind();
            try {
                irisGpuBuffer.upload(meshData);
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            if (byteBufferBuilder != null) {
                byteBufferBuilder.close();
            }
        }
        irisGpuBufferVertexSize = vertexSize;
        irisGpuBufferBrightness = brightness;
        irisGpuBufferPackedLight = packedLight;
        irisGpuBufferPackedOverlay = packedOverlay;
        irisGpuBufferReadAlpha = readAlpha;
        irisGpuBufferValid = true;
        irisGpuDirtyVertices.clear();
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("gpu.uploadStatic.iris.full", uploadStart,
                    "vertices=" + numVertices);
        }
    }

    private static void putPackedUV(long pointer, int packedUv) {
        if (IS_LITTLE_ENDIAN) {
            MemoryUtil.memPutInt(pointer, packedUv);
        } else {
            MemoryUtil.memPutShort(pointer, (short)(packedUv & '\uffff'));
            MemoryUtil.memPutShort(pointer + 2L, (short)(packedUv >> 16 & '\uffff'));
        }
    }

    private static void putNormal(long pointer, float x, float y, float z) {
        MemoryUtil.memPutByte(pointer, (byte) ((int) (x * 127) & 0xFF));
        MemoryUtil.memPutByte(pointer + 1L, (byte) ((int) (y * 127) & 0xFF));
        MemoryUtil.memPutByte(pointer + 2L, (byte) ((int) (z * 127) & 0xFF));
        MemoryUtil.memPutByte(pointer + 3L, (byte) 0);
    }

    private static int getFormatOffset(VertexFormat format, VertexFormatElement element) {
        int offset = 0;
        for (VertexFormatElement current : format.getElements()) {
            if (current.equals(element)) {
                return offset;
            }
            offset += current.byteSize();
        }
        return -1;
    }

    public void ensureBuilding() {
        if (!building) throw new IllegalStateException("Not building vertex buffer");
    }

    public void addVertex(ByteBuffer vertexData, int offset) {
        ensureBuilding();
        checkClosed();
        if (vertexData.capacity() != vertexSize) {
            throw new IllegalArgumentException("Vertex data length does not match vertex size");
        }
        ByteBuffer src = vertexData.duplicate();
        src.clear();
        src.limit(vertexSize);
        ByteBuffer dst = buffer.duplicate();
        dst.position(offset * this.vertexSize);
        dst.put(src);
        vertexData.clear();
    }

    private void includeBounds(float x, float y, float z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    private void includeBounds(Bounds bounds) {
        if (bounds == null || !bounds.hasBounds()) return;
        includeBounds(bounds.minX, bounds.minY, bounds.minZ);
        includeBounds(bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    public boolean hasBounds() {
        return minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    public AABB getBounds(@Nullable Vector3f position) {
        double x = position == null ? 0.0 : position.x();
        double y = position == null ? 0.0 : position.y();
        double z = position == null ? 0.0 : position.z();
        return new AABB(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    private void resetBounds() {
        minX = Float.POSITIVE_INFINITY;
        minY = Float.POSITIVE_INFINITY;
        minZ = Float.POSITIVE_INFINITY;
        maxX = Float.NEGATIVE_INFINITY;
        maxY = Float.NEGATIVE_INFINITY;
        maxZ = Float.NEGATIVE_INFINITY;
    }

    private void setSkinningData(Model model, ArrayList<Vertex> vertices, ArrayList<Mesh> meshes, Mesh[] tangentMeshes) {
        this.skinningVertices = vertices.toArray(new Vertex[0]);
        this.skinningMeshes = meshes.toArray(new Mesh[0]);
        this.tangentMeshes = tangentMeshes;
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
        HashMap<Bone, Transform> uniqueBindInverses = new HashMap<>();
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
                Pair<Transform, Transform> bindTransforms = model.getSkeleton().getBoneTransforms().get(bone);
                Transform bindInverse = bindTransforms == null ? null : bindTransforms.getSecond();
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

        // GPU 部分
        this.gpuSkinningBones = model.getSkeleton().getBones();

        HashMap<Bone, Integer> gpuBoneIndex = new HashMap<>();
        for (int i = 0; i < gpuSkinningBones.length; i++) {
            Bone bone = gpuSkinningBones[i];
            gpuBoneIndex.put(bone, i);
            uniqueBindInverses.put(bone, model.getSkeleton().getBindingInverse(bone));
        }
        this.gpuSkinningBoneIndex = gpuBoneIndex;
        this.gpuSkinningBindInverses = uniqueBindInverses;
        if (bufOffsets.get(RenderState.BONE_INDICES) == null || bufOffsets.get(RenderState.BONE_WEIGHTS) == null) {
            gpuSkinningDataReady = false;
            return;
        }
        writeGpuSkinningVertexData(vertices);
    }

    private void writeGpuSkinningVertexData(ArrayList<Vertex> vertices) {
        int boneIndexOffset = bufOffsets.get(RenderState.BONE_INDICES);
        int boneWeightOffset = bufOffsets.get(RenderState.BONE_WEIGHTS);
        int weightedVertices = 0;
        for (int i = 0; i < vertices.size(); i++) {
            int indexOffset = i * vertexSize + boneIndexOffset;
            int weightOffset = i * vertexSize + boneWeightOffset;
            for (int slot = 0; slot < 4; slot++) {
                buffer.putInt(indexOffset + slot * 4, 0);
                buffer.putFloat(weightOffset + slot * 4, 0.0f);
            }

            Pair<Bone, Float>[] weights = vertices.get(i).getBinding().getWeights();
            int slots = Math.min(weights.length, 4);
            float totalWeight = 0.0f;
            for (int slot = 0; slot < slots; slot++) {
                Pair<Bone, Float> weight = weights[slot];
                Integer gpuIndex = gpuSkinningBoneIndex.get(weight.getFirst());
                if (gpuIndex == null) continue;
                buffer.putInt(indexOffset + slot * 4, gpuIndex);
                buffer.putFloat(weightOffset + slot * 4, weight.getSecond());
                totalWeight += weight.getSecond();
            }
            if (totalWeight > 0.0f) {
                weightedVertices++;
            }
        }
        gpuSkinningDataReady = weightedVertices > 0 && gpuSkinningBones.length > 0;
    }

    private void captureBaseTangents() {
        if (baseTangents.length != skinningVertices.length * 4) {
            baseTangents = new float[skinningVertices.length * 4];
        }
        for (int i = 0; i < skinningVertices.length; i++) {
            int tangentOffset = getBufPos(i, RenderState.TANGENT);
            int componentOffset = i * 4;
            baseTangents[componentOffset] = buffer.getFloat(tangentOffset);
            baseTangents[componentOffset + 1] = buffer.getFloat(tangentOffset + 4);
            baseTangents[componentOffset + 2] = buffer.getFloat(tangentOffset + 8);
            baseTangents[componentOffset + 3] = buffer.getFloat(tangentOffset + 12);
        }
    }

    @Override
    public void updateForVersion(ModelInstance modelInstance, Bridge<?> bridge) {
        checkClosed();
        SkeletonInstance skeleton = modelInstance.getSkeletonInstance();
        int vertexCount = skinningVertices.length;
        if (vertexCount == 0) return;
        if ((isGpuSkinningEnabled() || isIrisGpuSkinningEnabled()) && gpuSkinningDataReady) {
            long uploadStart = ModelProfiler.start();
            uploadGpuSkinningTransforms(skeleton);
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.gpu.uploadBones", uploadStart,
                        "bones=" + gpuSkinningBones.length +
                                ", texels=" + (gpuSkinningBones.length * 9));
            }
            return;
        }
        if (skeleton.isLastFullUpdate()) {
            long updateStart = ModelProfiler.start();
            updateAllSkinning(modelInstance, bridge, skeleton, vertexCount);
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.full", updateStart,
                        "vertices=" + vertexCount + ", reason=skeletonFull");
            }
            return;
        }
        long collectStart = ModelProfiler.start();
        BitSet dirtyIndices = collectDirtySkinningIndices(skeleton.getLastDirtyBones(), vertexCount);
        int dirtyCount = dirtyIndices.cardinality();
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
            updateAllSkinning(modelInstance, bridge, skeleton, vertexCount);
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.full", updateStart,
                        "vertices=" + vertexCount + ", dirty=" + dirtyCount + ", reason=threshold");
            }
            return;
        }
        long updateStart = ModelProfiler.start();
        invalidateCpuUploadCaches();
        boolean recalculateTangents = recalculateDynamicTangents();
        Bounds bounds = new Bounds();
        HashSet<Mesh> dirtyMeshes = new HashSet<>();
        long skinningStart = ModelProfiler.start();
        int start = dirtyIndices.nextSetBit(0);
        while (start >= 0) {
            int end = dirtyIndices.nextClearBit(start);
            updateSkinningRange(modelInstance, bridge, skeleton, start, end, bounds, !recalculateTangents, false);
            dirtyMeshes.addAll(Arrays.asList(skinningMeshes).subList(start, end));
            start = dirtyIndices.nextSetBit(end);
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.cpu.partial.vertices", skinningStart,
                    "dirty=" + dirtyCount);
        }
        includeBounds(bounds);
        if (recalculateTangents) {
            long tangentStart = ModelProfiler.start();
            for (Mesh mesh : dirtyMeshes) {
                modifier.calculateTangent(mesh);
            }
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("skinning.cpu.partial.tangents", tangentStart,
                        "dirtyMeshes=" + dirtyMeshes.size());
            }
        }
        long dirtyUploadStart = ModelProfiler.start();
        Set<Mesh> dirtyTangentMeshes = recalculateTangents ? dirtyMeshes : Collections.emptySet();
        markStaticGpuDirty(dirtyIndices, dirtyTangentMeshes);
        markIrisGpuDirty(dirtyIndices, dirtyTangentMeshes);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.cpu.partial.markUpload", dirtyUploadStart,
                    "dirtyMeshes=" + (recalculateTangents ? dirtyMeshes.size() : 0));
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skinning.cpu.partial", updateStart,
                    "vertices=" + numVertices +
                            ", dirty=" + dirtyCount +
                            ", dirtyBones=" + skeleton.getLastDirtyBones().size() +
                            ", dirtyMeshes=" + dirtyMeshes.size() +
                            ", tangents=" + (recalculateTangents ? "mesh" : "skinned"));
        }
    }

    private void uploadGpuSkinningTransforms(SkeletonInstance skeleton) {
        RenderSystem.assertOnRenderThread();
        ensureGpuSkinningObjects();
        ensureGpuBoneTransformUploadCache(gpuSkinningBones.length * 9 * 4);
        gpuBoneTransformUploadCache.clear();
        Map<Bone, Transform> absoluteTransforms = skeleton.getAbsoluteTransforms();
        Transform identity = new Transform().setIdentity();
        for (Bone bone : gpuSkinningBones) {
            putTransform(gpuBoneTransformUploadCache,
                    absoluteTransforms.getOrDefault(bone, identity),
                    gpuSkinningBindInverses.getOrDefault(bone, identity));
        }
        gpuBoneTransformUploadCache.flip();
        int previousTextureBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        try {
            GlStateManager._glBindBuffer(GL31.GL_TEXTURE_BUFFER, gpuBoneTransformBufferId);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, gpuBoneTransformUploadCache, GL15.GL_DYNAMIC_DRAW);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gpuBoneTransformTextureId);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, gpuBoneTransformBufferId);
        } finally {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTextureBinding);
            GlStateManager._glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }

    private void ensureGpuSkinningObjects() {
        if (gpuBoneTransformBufferId == 0) {
            gpuBoneTransformBufferId = GL15.glGenBuffers();
        }
        if (gpuBoneTransformTextureId == 0) {
            gpuBoneTransformTextureId = GL11.glGenTextures();
        }
    }

    private void ensureGpuBoneTransformUploadCache(int floatCount) {
        if (gpuBoneTransformUploadCache != null && gpuBoneTransformUploadCache.capacity() >= floatCount) {
            return;
        }
        if (gpuBoneTransformUploadCache != null) {
            MemoryUtil.memFree(gpuBoneTransformUploadCache);
        }
        gpuBoneTransformUploadCache = MemoryUtil.memAllocFloat(floatCount);
    }

    private static void putTransform(FloatBuffer target, Transform absTransform, Transform bindInverse) {
        Matrix4f trans = new Matrix4f();
        trans.set(absTransform.transform());
        target.put(trans.m00()).put(trans.m01()).put(trans.m02()).put(trans.m30());
        target.put(trans.m10()).put(trans.m11()).put(trans.m12()).put(trans.m31());
        target.put(trans.m20()).put(trans.m21()).put(trans.m22()).put(trans.m32());

        trans.set(bindInverse.transform());
        target.put(trans.m00()).put(trans.m01()).put(trans.m02()).put(trans.m30());
        target.put(trans.m10()).put(trans.m11()).put(trans.m12()).put(trans.m31());
        target.put(trans.m20()).put(trans.m21()).put(trans.m22()).put(trans.m32());

        Matrix3f norm = absTransform.normal();
        target.put(norm.m00()).put(norm.m01()).put(norm.m02()).put(0.0f);
        target.put(norm.m10()).put(norm.m11()).put(norm.m12()).put(0.0f);
        target.put(norm.m20()).put(norm.m21()).put(norm.m22()).put(0.0f);
    }

    private void markStaticGpuDirty(BitSet dirtyIndices, Set<Mesh> dirtyMeshes) {
        if (!staticGpuBufferValid || staticGpuBuffer == null) {
            return;
        }
        for (int vertexIndex = dirtyIndices.nextSetBit(0);
             vertexIndex >= 0;
             vertexIndex = dirtyIndices.nextSetBit(vertexIndex + 1)) {
            if (vertexIndex < numVertices) {
                staticGpuDirtyVertices.set(vertexIndex);
            }
        }
        for (Mesh mesh : dirtyMeshes) {
            markMeshVerticesDirty(mesh);
        }
    }

    private void markIrisGpuDirty(BitSet dirtyIndices, Set<Mesh> dirtyMeshes) {
        if (!irisGpuBufferValid || irisGpuBuffer == null) {
            return;
        }
        for (int i = dirtyIndices.nextSetBit(0); i >= 0; i = dirtyIndices.nextSetBit(i + 1)) {
            if (i < numVertices) {
                irisGpuDirtyVertices.set(i);
            }
        }
        for (Mesh mesh : dirtyMeshes) {
            markIrisMeshVerticesDirty(mesh);
        }
    }

    private void markIrisMeshVerticesDirty(Mesh mesh) {
        for (Vertex vertex : mesh.getVertices()) {
            HashMap<Mesh, Integer[]> byMesh = vertexMap.get(vertex);
            if (byMesh == null) continue;
            Integer[] indices = byMesh.get(mesh);
            if (indices == null) continue;
            for (Integer index : indices) {
                if (index != null && index >= 0 && index < numVertices) {
                    irisGpuDirtyVertices.set(index);
                }
            }
        }
    }

    private void markMeshVerticesDirty(Mesh mesh) {
        for (Vertex vertex : mesh.getVertices()) {
            HashMap<Mesh, Integer[]> byMesh = vertexMap.get(vertex);
            if (byMesh == null) continue;
            Integer[] indices = byMesh.get(mesh);
            if (indices == null) continue;
            for (Integer index : indices) {
                if (index != null && index >= 0 && index < numVertices) {
                    staticGpuDirtyVertices.set(index);
                }
            }
        }
    }

    private void updateAllSkinning(ModelInstance modelInstance, Bridge<?> bridge, SkeletonInstance skeleton, int vertexCount) {
        invalidateUploadCache();
        resetBounds();
        Bounds bounds = new Bounds();
        boolean recalculateTangents = recalculateDynamicTangents();
        updateSkinningRange(modelInstance, bridge, skeleton, 0, vertexCount, bounds, !recalculateTangents, false);
        includeBounds(bounds);
        if (recalculateTangents) {
            for (Mesh mesh : tangentMeshes) {
                modifier.calculateTangent(mesh);
            }
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

    private BitSet collectDirtySkinningIndices(Set<Bone> dirtyBones, int vertexCount) {
        dirtySkinningIndices.clear();
        if (dirtyBones == null || dirtyBones.isEmpty()) {
            return dirtySkinningIndices;
        }
        for (Bone bone : dirtyBones) {
            int[] indices = skinningIndicesByBone.get(bone);
            if (indices == null) continue;
            for (int index : indices) {
                if (index >= 0 && index < vertexCount) {
                    dirtySkinningIndices.set(index);
                }
            }
        }
        return dirtySkinningIndices;
    }

    private void updateSkinningRange(ModelInstance modelInstance, Bridge<?> bridge, SkeletonInstance skeleton,
                                     int startInclusive, int endExclusive, Bounds bounds, boolean updateTangents, boolean isRecursive) {
        int len = endExclusive - startInclusive;
        int lenEachThread = MULTI_THREADED_SKINNING_THRESHOLD;
        if (!isRecursive && len > lenEachThread) {
            // 这里拆分任务到多个子线程
            int taskCount = (int) Math.ceil((float) len / (float) lenEachThread);
            CompletableFuture<Void>[] futures = new CompletableFuture[taskCount];
            for (int task = 0; task < taskCount; task++) {
                int taskStart = startInclusive + task * lenEachThread;
                int taskEnd = Math.min(taskStart + lenEachThread, endExclusive);
                futures[task] = (CompletableFuture.runAsync(() ->
                        updateSkinningRange(modelInstance, bridge, skeleton,
                        taskStart, taskEnd, bounds, updateTangents, true), executor));
            }
            // 等待所有子线程完成
            CompletableFuture.allOf(futures).join();
            return;
        }
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
            putSkinnedVertex(i, position, normal, updateTangents ? tangent : null);
            bounds.include(position);
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

    private void putSkinnedVertex(int index, Vector3f position, Vector3f normal, @Nullable Vector4f tangent) {
        int posOffset = getBufPos(index, VertexFormatElement.POSITION);
        buffer.putFloat(posOffset, position.x());
        buffer.putFloat(posOffset + 4, position.y());
        buffer.putFloat(posOffset + 8, position.z());

        int normalOffset = getBufPos(index, VertexFormatElement.NORMAL);
        if (normal.lengthSquared() > 0f) {
            normal.normalize();
        }
        buffer.put(normalOffset, (byte) ((int) (normal.x() * 127) & 0xFF));
        buffer.put(normalOffset + 1, (byte) ((int) (normal.y() * 127) & 0xFF));
        buffer.put(normalOffset + 2, (byte) ((int) (normal.z() * 127) & 0xFF));

        if (tangent != null) {
            int tangentOffset = getBufPos(index, RenderState.TANGENT);
            float tangentLength = (float) Math.sqrt(tangent.x() * tangent.x() + tangent.y() * tangent.y() + tangent.z() * tangent.z());
            if (tangentLength > 0.0f && !Float.isNaN(tangentLength)) {
                tangent.x /= tangentLength;
                tangent.y /= tangentLength;
                tangent.z /= tangentLength;
            }
            buffer.putFloat(tangentOffset, tangent.x());
            buffer.putFloat(tangentOffset + 4, tangent.y());
            buffer.putFloat(tangentOffset + 8, tangent.z());
            buffer.putFloat(tangentOffset + 12, tangent.w());
        }
    }

    private static class Bounds {
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        private void include(Vector3f position) {
            minX = Math.min(minX, position.x());
            minY = Math.min(minY, position.y());
            minZ = Math.min(minZ, position.z());
            maxX = Math.max(maxX, position.x());
            maxY = Math.max(maxY, position.y());
            maxZ = Math.max(maxZ, position.z());
        }

        private boolean hasBounds() {
            return minX <= maxX && minY <= maxY && minZ <= maxZ;
        }
    }

    public void frozen(HashMap<Vertex, HashMap<Mesh, Integer[]>> boneTransformMap) {
        ensureBuilding();
        checkClosed();
        this.vertexMap.putAll(boneTransformMap);
        this.building = false;
    }

    public static class Builder implements VertexConsumer {

        private final ExecutorService executor;
        private final KsgVertexBuffer vertexBuffer;
        private final VertexFormat format;
        private final int vertexDataSize;
        private final HashMap<Vertex, HashMap<Mesh, ArrayList<Integer>>> boneVertexMap;
        private final ArrayList<Vertex> vertices;
        private final ArrayList<Vertex> skinningVertices;
        private final ArrayList<Mesh> skinningMeshes;
        private final VertexFormatElement uv1Element;
        private final VertexFormatElement uv2Element;
        private final int positionOffset;
        private final int colorOffset;
        private final int meshColorOffset;
        private final int normalOffset;
        private final int uv0Offset;
        private final int uv1Offset;
        private final int uv2Offset;
        private final int tangentOffset;
        private final int boneIndexOffset;
        private final int boneWeightOffset;
        private final int boneBindingTypeOffset;
        private final int sdefR0Offset;
        private final int sdefR1Offset;
        private final int sdefCOffset;

        private int vertexIndex = 0;
        private int indexVertexInMesh;
        private Vector3f position;
        private int color;
        private Vector3f normal;
        private Vector2f uv0;
        private Vector2f uv1;
        private Vector2f uv2;
        private Vector4f tangent;
        private int boneIndex1, boneIndex2, boneIndex3, boneIndex4;
        private float boneWeight1, boneWeight2, boneWeight3, boneWeight4;
        private Vector3f sdefR0, sdefR1, sdefC;
        private int boneBindingType;
        private boolean modifying;
        private Pair<Mesh, Integer[]> modifyingVertexIndices;
        private int buildingIndex;

        public Builder(Model model, VertexFormat format, ExecutorService executor) {
            this.executor = executor;
            this.format = format;
            this.vertexDataSize = format.getVertexSize() - shouldIgnoredBytes(format) + 4;
            this.uv1Element = findUvElement(format, 1);
            this.uv2Element = findUvElement(format, 2);
            this.positionOffset = getOffsetFor(VertexFormatElement.POSITION);
            this.colorOffset = getOffsetFor(VertexFormatElement.COLOR);
            this.meshColorOffset = colorOffset + 4;
            this.normalOffset = getOffsetFor(VertexFormatElement.NORMAL);
            this.uv0Offset = getOffsetFor(VertexFormatElement.UV0);
            this.uv1Offset = uv1Element == null ? -1 : getOffsetFor(uv1Element);
            this.uv2Offset = uv2Element == null ? -1 : getOffsetFor(uv2Element);
            this.boneIndexOffset = getOffsetFor(RenderState.BONE_INDICES);
            this.boneWeightOffset = getOffsetFor(RenderState.BONE_WEIGHTS);
            this.boneBindingTypeOffset = getOffsetFor(RenderState.BONE_BINDING_TYPE);
            this.sdefR0Offset = getOffsetFor(RenderState.SDEF_R0);
            this.sdefR1Offset = getOffsetFor(RenderState.SDEF_R1);
            this.sdefCOffset = getOffsetFor(RenderState.SDEF_C);

            this.tangentOffset = getOffsetFor(RenderState.TANGENT);
            this.vertexBuffer = new KsgVertexBuffer(
                    4 * model.getMeshes().length,
                    vertexDataSize, format, this, executor
            );
            position = new Vector3f();
            normal = new Vector3f();
            uv0 = new Vector2f();
            uv1 = new Vector2f();
            uv2 = new Vector2f();
            tangent = new Vector4f();
            sdefR0 = new Vector3f();
            sdefR1 = new Vector3f();
            sdefC = new Vector3f();

            reset();
            indexVertexInMesh = 0;
            boneVertexMap = new HashMap<>();
            vertices = new ArrayList<>(4 * model.getMeshes().length);
            skinningVertices = new ArrayList<>(4 * model.getMeshes().length);
            skinningMeshes = new ArrayList<>(4 * model.getMeshes().length);
            this.modifying = false;
            this.modifyingVertexIndices = null;
            this.buildingIndex = 0;
        }

        private static VertexFormatElement findUvElement(VertexFormat format, int uvIndex) {
            for (VertexFormatElement element : format.getElements()) {
                if (!element.usage().equals(VertexFormatElement.Usage.UV)) continue;
                if (element.index() == uvIndex + 2) return element;
            }
            return null;
        }

        private int shouldIgnoredBytes(VertexFormat format) {
            int ignoredBytes = 0;
            for (VertexFormatElement element : format.getElements()) {
                if (element.equals(VertexFormatElement.UV1) ||
                        element.equals(VertexFormatElement.UV2)) {
                    ignoredBytes += element.byteSize();
                }
            }
            return ignoredBytes;
        }

        private void reset() {
            position.set(0f, 0f, 0f);
            color = 0xFFFFFFFF;
            normal.set(0f, 0f, 0f);
            uv0.set(0f, 0f);
            uv1.set(0f, 0f);
            uv2.set(0f, 0f);
            tangent.set(0f, 0f, 0f, 0f);
            boneIndex1 = boneIndex2 = boneIndex3 = boneIndex4 = 0;
            boneWeight1 = boneWeight2 = boneWeight3 = boneWeight4 = 0;
            boneBindingType = 0;
            sdefR0.set(0f, 0f, 0f);
            sdefR1.set(0f, 0f, 0f);
            sdefC.set(0f, 0f, 0f);
        }

        public int getOffsetFor(VertexFormatElement element) {
            List<VertexFormatElement> elements = format.getElements();
            int index = elements.indexOf(element);
            if (index == -1) return -1;
            int offset = 0;
            for (int i = 0; i < index; i++) {
                VertexFormatElement e = elements.get(i);
                if (e.equals(VertexFormatElement.UV1) ||
                        e.equals(VertexFormatElement.UV2)) continue;
                if (e.equals(VertexFormatElement.COLOR)) offset += e.byteSize() * 2;
                else offset += e.byteSize();
            }
            return offset;
        }

        public void ensureBuilding() {
            if (!vertexBuffer.building) throw new IllegalStateException("Not building vertex buffer");
        }

        public void ensureFrozen() {
            if (!modifying) throw new IllegalStateException("Vertex buffer is still building");
        }

        public VertexFormatElement ensureHasUv(int i) {
            if (i < 1) return VertexFormatElement.UV0;
            VertexFormatElement element = switch (i) {
                case 1 -> uv1Element;
                case 2 -> uv2Element;
                default -> null;
            };
            if (element != null) {
                return element;
            }
            throw new IllegalStateException("Vertex format does not have UV" + i);
        }

        public Builder modifyVertex(Vertex vertex, Mesh mesh, float x, float y, float z) {
            ensureFrozen();
            this.position.set(x, y, z);
            HashMap<Mesh, Integer[]> map = vertexBuffer.vertexMap.get(vertex);
            if (map ==null) {
                modifyingVertexIndices = null;
                return this;
            }
            Integer[] indices = map.get(mesh);
            if (indices == null) {
                modifyingVertexIndices = null;
                return this;
            }
            modifyingVertexIndices = Pair.of(mesh, indices);
            return this;
        }

        @Override
        public Builder addVertex(float x, float y, float z) {
            this.ensureBuilding();
            if (indexVertexInMesh > 4) {
                throw new IllegalStateException("Expected quad export after fourth vertex");
            }
            if (vertexIndex > vertexBuffer.numVertices) {
                throw new IllegalStateException("Vertex index exceeds vertex buffer capacity");
            }
            this.position.set(x, y, z);
            return this;
        }

        @Override
        public Builder setColor(int r, int g, int b, int a) {
            this.color = FastColor.ABGR32.color(a, b, g, r);
            return this;
        }

        public Builder setColor(Vector4f color) {
            this.color = FastColor.ABGR32.color(
                    (int) (color.w * 255),
                    (int) (color.z * 255),
                    (int) (color.y * 255),
                    (int) (color.x * 255));
            return this;
        }

        @Override
        public @NotNull Builder setUv(float v, float v1) {
            this.uv0.set(v, v1);
            return this;
        }

        public @NotNull Builder setUv(Vector2f uv) {
            this.uv0.set(uv);
            return this;
        }

        public @NotNull Builder setUv(int uvIndex, Vector2f uv) {
            if (uvIndex < 1) return setUv(uv);
            ensureHasUv(uvIndex);
            switch (uvIndex) {
                case 1 -> this.uv1.set(uv);
                case 2 -> this.uv2.set(uv);
                default -> throw new IllegalArgumentException("Invalid UV index: " + uvIndex);
            }
            return this;
        }

        public @NotNull Builder setBoneBindingType(int type) {
            this.boneBindingType = type;
            return this;
        }

        public @NonNull Builder setBoneAndWeight(int index, int boneIndex, float weight) {
            switch (index) {
                case 0 -> {
                    this.boneIndex1 = boneIndex;
                    this.boneWeight1 = weight;
                }
                case 1 -> {
                    this.boneIndex2 = boneIndex;
                    this.boneWeight2 = weight;
                }
                case 2 -> {
                    this.boneIndex3 = boneIndex;
                    this.boneWeight3 = weight;
                }
                case 3 -> {
                    this.boneIndex4 = boneIndex;
                    this.boneWeight4 = weight;
                }
                default -> throw new IllegalArgumentException("Invalid bone index: " + index);
            }
            return this;
        }

        public @NonNull Builder setSdefData(SDEFData data) {
            this.sdefR0.set(data.r0());
            this.sdefR1.set(data.r1());
            this.sdefC.set(data.c());
            return this;
        }

        public @NotNull Builder setUv1(Vector2f uv) {
            return setUv(1, uv);
        }

        public @NotNull Builder setUv2(Vector2f uv) {
            return setUv(2, uv);
        }

        @Override
        @Deprecated
        public @NotNull Builder setUv1(int i, int i1) {
            return this;
        }

        @Override
        @Deprecated
        public @NotNull Builder setUv2(int i, int i1) {
            return this;
        }

        @Override
        public @NotNull Builder setNormal(float x, float y, float z) {
            this.normal.set(x, y, z);
            return this;
        }

        public @NotNull Builder setNormal(Vector3f normal) {
            return setNormal(normal.x(), normal.y(), normal.z());
        }

        public void ensureModifying() {
            if (modifying && modifyingVertexIndices == null) {
                throw new IllegalStateException("No vertex is being modified");
            }
        }

        public Builder update(Vector4f meshColor, Mesh mesh) {
            ensureModifying();
            for (Integer i : modifyingVertexIndices.getSecond()) {
                int j = (int) i;
                pack(j, vertices.get(j), mesh, meshColor);
            }
            return this;
        }

        public Builder pack(Vertex vertex, Mesh mesh, Vector4f meshColor) {
            ensureBuilding();
            return pack(0, vertex, mesh, meshColor);
        }

        public Builder pack(int index, Vertex vertex, Mesh mesh, Vector4f meshColor) {
            try (MemoryStack memory = MemoryStack.stackPush()) {
                if (!modifying) index = vertexIndex;
                ByteBuffer byteBuffer = memory.malloc(vertexDataSize);
                byteBuffer.putFloat(positionOffset, position.x());
                byteBuffer.putFloat(positionOffset + 4, position.y());
                byteBuffer.putFloat(positionOffset + 8, position.z());

                byteBuffer.putInt(colorOffset, color);
                int meshColorInt = FastColor.ABGR32.color(
                        (int) (meshColor.w * 255),
                        (int) (meshColor.z * 255),
                        (int) (meshColor.y * 255),
                        (int) (meshColor.x * 255)
                );
                byteBuffer.putInt(meshColorOffset, meshColorInt);

                byteBuffer.put(normalOffset, (byte) ((int) (normal.x() * 127) & 0xFF));
                byteBuffer.put(normalOffset + 1, (byte) ((int) (normal.y() * 127) & 0xFF));
                byteBuffer.put(normalOffset + 2, (byte) ((int) (normal.z() * 127) & 0xFF));

                byteBuffer.putFloat(uv0Offset, uv0.x());
                byteBuffer.putFloat(uv0Offset + 4, uv0.y());

                if (uv1Offset >= 0) {
                    byteBuffer.putFloat(uv1Offset, uv1.x());
                    byteBuffer.putFloat(uv1Offset + 4, uv1.y());
                }

                if (uv2Offset >= 0) {
                    byteBuffer.putFloat(uv2Offset, uv2.x());
                    byteBuffer.putFloat(uv2Offset + 4, uv2.y());
                }

                if (tangentOffset >= 0) {
                    byteBuffer.putFloat(tangentOffset, tangent.x());
                    byteBuffer.putFloat(tangentOffset + 4, tangent.y());
                    byteBuffer.putFloat(tangentOffset + 8, tangent.z());
                    byteBuffer.putFloat(tangentOffset + 12, tangent.w());
                }

                if (boneBindingTypeOffset > 0 && boneIndexOffset > 0 && boneWeightOffset > 0) {
                    byteBuffer.putInt(boneBindingTypeOffset, boneBindingType);

                    byteBuffer.putInt(boneIndexOffset, boneIndex1);
                    byteBuffer.putInt(boneIndexOffset + 4, boneIndex2);
                    byteBuffer.putInt(boneIndexOffset + 8, boneIndex3);
                    byteBuffer.putInt(boneIndexOffset + 12, boneIndex4);

                    byteBuffer.putFloat(boneWeightOffset, boneWeight1);
                    byteBuffer.putFloat(boneWeightOffset + 4, boneWeight2);
                    byteBuffer.putFloat(boneWeightOffset + 8, boneWeight3);
                    byteBuffer.putFloat(boneWeightOffset + 12, boneWeight4);
                }

                if (sdefR0Offset > 0 && sdefR1Offset > 0 && sdefCOffset > 0) {
                    byteBuffer.putFloat(sdefR0Offset, sdefR0.x());
                    byteBuffer.putFloat(sdefR0Offset + 4, sdefR0.y());
                    byteBuffer.putFloat(sdefR0Offset + 8, sdefR0.z());
                    byteBuffer.putFloat(sdefR1Offset, sdefR1.x());
                    byteBuffer.putFloat(sdefR1Offset + 4, sdefR1.y());
                    byteBuffer.putFloat(sdefR1Offset + 8, sdefR1.z());
                    byteBuffer.putFloat(sdefCOffset, sdefC.x());
                    byteBuffer.putFloat(sdefCOffset + 4, sdefC.y());
                    byteBuffer.putFloat(sdefCOffset + 8, sdefC.z());
                }

                vertexBuffer.invalidateUploadCache();
                vertexBuffer.includeBounds(position.x(), position.y(), position.z());
                vertexBuffer.addVertex(byteBuffer, index);
                if (!modifying) {
                    boneVertexMap.computeIfAbsent(vertex, k -> new HashMap<>()).computeIfAbsent(mesh, m -> new ArrayList<>()).add(index);
                    vertices.add(vertex);
                    recordSkinningVertex(vertex, mesh);
                    indexVertexInMesh ++;
                    vertexIndex++;
                    buildingIndex += vertexDataSize;
                }
            }
            this.reset();
            return this;
        }

        private void recordSkinningVertex(Vertex vertex, Mesh mesh) {
            skinningVertices.add(vertex);
            skinningMeshes.add(mesh);
        }

        public Builder endMesh(Mesh mesh) {
            if (indexVertexInMesh < 1) return this;
            if (indexVertexInMesh == 1) {
                throw new IllegalStateException("Mesh ended with only 1 vertex");
            }
            if (indexVertexInMesh < 4) {
                ByteBuffer built = vertexBuffer.buffer;
                if (indexVertexInMesh < 3) {
                    ByteBuffer firstVertex = built.slice(buildingIndex - 2 * vertexDataSize, vertexDataSize);
                    ByteBuffer secondVertex = built.slice(buildingIndex - vertexDataSize, vertexDataSize);
                    Vertex vertex1 = vertices.get(vertices.size() - 2);
                    Vertex vertex2 = vertices.getLast();
                    for (int i = 0; i < vertexDataSize; i++) {
                        built.put(buildingIndex + i, firstVertex.get(i));
                    }
                    buildingIndex += vertexDataSize;
                    for (int i = 0; i < vertexDataSize; i++) {
                        built.put(buildingIndex + i, secondVertex.get(i));
                    }
                    vertices.add(vertex1);
                    vertices.add(vertex2);
                    int vertexIndex1 = vertexIndex++;
                    int vertexIndex2 = vertexIndex++;
                    boneVertexMap.computeIfAbsent(vertex1, k -> new HashMap<>()).computeIfAbsent(mesh, m -> new ArrayList<>()).add(vertexIndex1);
                    boneVertexMap.computeIfAbsent(vertex2, k -> new HashMap<>()).computeIfAbsent(mesh, m -> new ArrayList<>()).add(vertexIndex2);
                    recordSkinningVertex(vertex1, mesh);
                    recordSkinningVertex(vertex2, mesh);
                    buildingIndex += vertexDataSize;
                } else {
                    ByteBuffer thirdVertex = built.slice(buildingIndex - vertexDataSize, vertexDataSize);
                    Vertex vertex3 = vertices.getLast();
                    for (int i = 0; i < vertexDataSize; i++) {
                        built.put(buildingIndex + i, thirdVertex.get(i));
                    }
                    vertices.add(vertex3);
                    int vertexIndex3 = vertexIndex++;
                    boneVertexMap.computeIfAbsent(vertex3, k -> new HashMap<>()).computeIfAbsent(mesh, m -> new ArrayList<>()).add(vertexIndex3);
                    recordSkinningVertex(vertex3, mesh);
                    buildingIndex += vertexDataSize;
                }
            }
            indexVertexInMesh = 0;
            return this;
        }

        public Builder calculateTangent(Mesh mesh) {
            Vertex[] meshVertices = mesh.getVertices();
            if (meshVertices.length < 3 || tangentOffset < 0) return this;
            for (int i = 0; i < meshVertices.length; i++) {
                Vertex self = meshVertices[i];
                Vertex edge1 = meshVertices[(i + meshVertices.length - 1) % meshVertices.length];
                Vertex edge2 = meshVertices[(i + 1) % meshVertices.length];
                int selfIndex = getFirstVertexIndex(self, mesh);
                int edge1Index = getFirstVertexIndex(edge1, mesh);
                int edge2Index = getFirstVertexIndex(edge2, mesh);
                if (selfIndex < 0 || edge1Index < 0 || edge2Index < 0) continue;
                calculateAndSetTangent(self, mesh, selfIndex, edge1Index, edge2Index);
            }
            return this;
        }

        private int getFirstVertexIndex(Vertex vertex, Mesh mesh) {
            HashMap<Mesh, Integer[]> map = vertexBuffer.vertexMap.get(vertex);
            if (map == null) return -1;
            Integer[] pointers = map.get(mesh);
            if (pointers == null || pointers.length == 0) return -1;
            return pointers[0];
        }

        private void calculateAndSetTangent(Vertex vertex, Mesh mesh, int selfIndex, int edge1Index, int edge2Index) {
            int selfPosOffset = vertexBuffer.getBufPos(selfIndex, VertexFormatElement.POSITION);
            int edge1PosOffset = vertexBuffer.getBufPos(edge1Index, VertexFormatElement.POSITION);
            int edge2PosOffset = vertexBuffer.getBufPos(edge2Index, VertexFormatElement.POSITION);
            float selfX = vertexBuffer.buffer.getFloat(selfPosOffset);
            float selfY = vertexBuffer.buffer.getFloat(selfPosOffset + 4);
            float selfZ = vertexBuffer.buffer.getFloat(selfPosOffset + 8);
            float edge1X = vertexBuffer.buffer.getFloat(edge1PosOffset) - selfX;
            float edge1Y = vertexBuffer.buffer.getFloat(edge1PosOffset + 4) - selfY;
            float edge1Z = vertexBuffer.buffer.getFloat(edge1PosOffset + 8) - selfZ;
            float edge2X = vertexBuffer.buffer.getFloat(edge2PosOffset) - selfX;
            float edge2Y = vertexBuffer.buffer.getFloat(edge2PosOffset + 4) - selfY;
            float edge2Z = vertexBuffer.buffer.getFloat(edge2PosOffset + 8) - selfZ;

            int selfUvOffset = vertexBuffer.getBufPos(selfIndex, VertexFormatElement.UV0);
            int edge1UvOffset = vertexBuffer.getBufPos(edge1Index, VertexFormatElement.UV0);
            int edge2UvOffset = vertexBuffer.getBufPos(edge2Index, VertexFormatElement.UV0);
            float selfU = vertexBuffer.buffer.getFloat(selfUvOffset);
            float selfV = vertexBuffer.buffer.getFloat(selfUvOffset + 4);
            float deltaUv1X = vertexBuffer.buffer.getFloat(edge1UvOffset) - selfU;
            float deltaUv1Y = vertexBuffer.buffer.getFloat(edge1UvOffset + 4) - selfV;
            float deltaUv2X = vertexBuffer.buffer.getFloat(edge2UvOffset) - selfU;
            float deltaUv2Y = vertexBuffer.buffer.getFloat(edge2UvOffset + 4) - selfV;

            float denominator = deltaUv1X * deltaUv2Y - deltaUv2X * deltaUv1Y;
            float factor = denominator == 0.0f ? 1.0f : 1.0f / denominator;
            float tangentX = factor * (deltaUv2Y * edge1X - deltaUv1Y * edge2X);
            float tangentY = factor * (deltaUv2Y * edge1Y - deltaUv1Y * edge2Y);
            float tangentZ = factor * (deltaUv2Y * edge1Z - deltaUv1Y * edge2Z);
            float tangentLength = (float) Math.sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
            if (tangentLength == 0.0f || Float.isNaN(tangentLength)) {
                setVertexTangent(vertex, mesh, 0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }
            tangentX /= tangentLength;
            tangentY /= tangentLength;
            tangentZ /= tangentLength;

            int normalOffset = vertexBuffer.getBufPos(selfIndex, VertexFormatElement.NORMAL);
            float normalX = ((float) vertexBuffer.buffer.get(normalOffset)) / 127f;
            float normalY = ((float) vertexBuffer.buffer.get(normalOffset + 1)) / 127f;
            float normalZ = ((float) vertexBuffer.buffer.get(normalOffset + 2)) / 127f;
            float biTangentX = factor * (tangentY * normalZ - tangentZ * normalY);
            float biTangentY = factor * (tangentZ * normalX - tangentX * normalZ);
            float biTangentZ = factor * (tangentX * normalY - tangentY * normalX);
            float bitangentLength = (float) Math.sqrt(
                    biTangentX * biTangentX + biTangentY * biTangentY + biTangentZ * biTangentZ
            );
            if (bitangentLength == 0.0f || Float.isNaN(bitangentLength)) {
                setVertexTangent(vertex, mesh, 0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }
            setVertexTangent(vertex, mesh,
                    biTangentX / bitangentLength,
                    biTangentY / bitangentLength,
                    biTangentZ / bitangentLength,
                    bitangentLength < 0.0f ? -1.0f : 1.0f);
        }

        @Nullable
        public TangentHelper.PosUVNormal getPosUVNormal(Vertex vertex, Mesh mesh) {
            HashMap<Mesh, Integer[]> map = vertexBuffer.vertexMap.get(vertex);
            if (map == null) return null;
            final Integer[] pointers = map.get(mesh);
            if (pointers == null || pointers.length == 0) {
                return null;
            }
            int index = pointers[0];
            int posOffset = vertexBuffer.getBufPos(index, VertexFormatElement.POSITION);
            Vector3f position = new Vector3f(
                    vertexBuffer.buffer.getFloat(posOffset),
                    vertexBuffer.buffer.getFloat(posOffset + 4),
                    vertexBuffer.buffer.getFloat(posOffset + 8)
            );
            int uvOffset = vertexBuffer.getBufPos(index, VertexFormatElement.UV0);
            Vector2f uv = new Vector2f(
                    vertexBuffer.buffer.getFloat(uvOffset),
                    vertexBuffer.buffer.getFloat(uvOffset + 4)
            );
            int normalOffset = vertexBuffer.getBufPos(index, VertexFormatElement.NORMAL);
            float nx = ((float) vertexBuffer.buffer.get(normalOffset)) / 127f;
            float ny = ((float) vertexBuffer.buffer.get(normalOffset + 1)) / 127f;
            float nz = ((float) vertexBuffer.buffer.get(normalOffset + 2)) / 127f;
            Vector3f norm = new Vector3f(nx, ny, nz);
            norm.normalize();
            return new TangentHelper.PosUVNormal(position, uv, norm);
        }

        public void setVertexTangent(Vertex vertex, Mesh mesh, Vector4f tangent) {
            setVertexTangent(vertex, mesh, tangent.x(), tangent.y(), tangent.z(), tangent.w());
        }

        public void setVertexTangent(Vertex vertex, Mesh mesh, float x, float y, float z, float w) {
            HashMap<Mesh, Integer[]> map = vertexBuffer.vertexMap.get(vertex);
            if (map == null) {
                return;
            }
            final Integer[] pointers = map.get(mesh);
            if (pointers == null || pointers.length == 0) {
                return;
            }
            for (Integer i : pointers) {
                int index = i;
                int tangentOffset = vertexBuffer.getBufPos(index, RenderState.TANGENT);
                vertexBuffer.buffer.putFloat(tangentOffset, x);
                vertexBuffer.buffer.putFloat(tangentOffset + 4, y);
                vertexBuffer.buffer.putFloat(tangentOffset + 8, z);
                vertexBuffer.buffer.putFloat(tangentOffset + 12, w);
            }
        }

        public KsgVertexBuffer build(Model model) {
            long freezeStart = ModelProfiler.start();
            HashMap<Vertex, HashMap<Mesh, Integer[]>> boneTransformMap = new HashMap<>();
            for (Vertex bone : boneVertexMap.keySet()) {
                HashMap<Mesh, ArrayList<Integer>> map = boneVertexMap.get(bone);
                HashMap<Mesh, Integer[]> intMap = new HashMap<>();
                for (Mesh mesh : map.keySet()) {
                    ArrayList<Integer> vertexIndices = map.get(mesh);
                    intMap.put(mesh, vertexIndices.toArray(new Integer[0]));
                }
                boneTransformMap.put(bone, intMap);
            }
            vertexBuffer.frozen(boneTransformMap);
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("vertexBuffer.freezeMap", freezeStart,
                        "vertices=" + boneTransformMap.size());
            }
            long skinningDataStart = ModelProfiler.start();
            vertexBuffer.setSkinningData(model, skinningVertices, skinningMeshes, model.getMeshes());
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("vertexBuffer.setSkinningData", skinningDataStart,
                        "skinningVertices=" + skinningVertices.size());
            }
            long tangentStart = ModelProfiler.start();
            for (Mesh mesh : model.getMeshes()) {
                calculateTangent(mesh);
            }
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("vertexBuffer.calculateTangents", tangentStart,
                        "meshes=" + model.getMeshes().length);
            }
            vertexBuffer.captureBaseTangents();
            this.vertices.clear();
            this.modifying = true;
            return vertexBuffer;
        }
    }
}
