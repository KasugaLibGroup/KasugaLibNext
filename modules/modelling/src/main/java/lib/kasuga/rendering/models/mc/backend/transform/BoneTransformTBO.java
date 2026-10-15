package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.backend.BonePalettePacker;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lombok.Getter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

public class BoneTransformTBO implements AutoCloseable {

    private static final int FLOATS_PER_BONE = 36;

    @Getter
    private final SkeletonInstance skeleton;

    private final Bone[] bones;
    private final Transform[] bindInverses;

    @Getter
    private final int boneCount;

    @Getter
    private int bufferId = 0;

    private int textureId = 0;
    private long gpuVersion = Long.MIN_VALUE;
    private final TextureUploadRing uploadRing = new TextureUploadRing();

    @Getter
    private FloatBuffer uploadCache;

    @Getter
    private boolean closed = false;

    @Getter
    private long skeletonVersion;

    public BoneTransformTBO(SkeletonInstance skeletonInstance) {
        this.skeleton = skeletonInstance;
        this.bones = skeletonInstance.getSkeleton().getBones();
        this.bindInverses = new Transform[bones.length];
        this.boneCount = bones.length;
        this.skeletonVersion = skeletonInstance.getVersion();

        Skeleton skl = skeleton.getSkeleton();
        for (int i = 0; i < bones.length; i++) {
            Bone b = bones[i];
            this.bindInverses[i] = skl.getBindingInverse(b);
        }

        stageTransforms();
    }

    public void uploadTransforms() {
        stageTransforms();
        skeletonVersion = skeleton.getVersion();
        gpuVersion = Long.MIN_VALUE;
        ensureGpuBuffer();
    }

    /** Batch consumers use this palette without allocating/uploading a per-instance GPU copy. */
    private void stageTransforms() {
        if (closed) {
            throw new IllegalStateException("BoneTransformTBO is already closed.");
        }

        if (boneCount == 0) return;

        ensureUploadCache();
        uploadCache.clear();

        Transform identity = new Transform();
        for (int i = 0; i < bones.length; i++) {
            Transform absTransform = skeleton
                    .getAbsoluteTransforms()
                    .getOrDefault(bones[i], identity);
            putTransform(uploadCache, absTransform, bindInverses[i]);
        }

        uploadCache.flip();
    }

    public int getTextureId() {
        ensureGpuBuffer();
        // Returning/binding the texture is also a conservative read mark for
        // existing callers that draw outside BackendInstance.
        if (textureId != 0) uploadRing.markSubmitted();
        return textureId;
    }

    private void ensureGpuBuffer() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("BoneTransformTBO is already closed.");
        if (boneCount == 0 || gpuVersion == skeletonVersion) return;
        refreshGpuBuffer();
        gpuVersion = skeletonVersion;
    }

    protected void refreshGpuBuffer() {
        textureId = uploadRing.upload(uploadCache);
        bufferId = uploadRing.bufferId();
    }

    public void markSubmitted() { uploadRing.markSubmitted(); }

    public void bindToTextureUnit(int textureUnit) {
        RenderSystem.assertOnRenderThread();
        ensureGpuBuffer();
        if (textureId == 0) return;
        GlStateManager._activeTexture(textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
        uploadRing.markSubmitted();
    }

    public boolean isValid() {
        return !closed && boneCount > 0 && uploadCache != null;
    }

    protected static void putTransform(FloatBuffer target, Transform absTransform, Transform bindInverse) {
        BonePalettePacker.put(target, absTransform.transform(), bindInverse.transform(), absTransform.normal());
    }

    public void updateForVersion() {
        if (closed) return;
        long currentVersion = skeleton.getVersion();
        if (currentVersion == skeletonVersion) return;

        long uploadStart = ModelProfiler.start();

        stageTransforms();

        skeletonVersion = currentVersion;
        if (ModelProfiler.enabled()) {
            ModelProfiler.record(
                    "Skinning.cpu.stageBones", uploadStart,
                    "bones=" + boneCount + ", texels=" + (boneCount * 9)
            );
        }
    }

    protected void ensureUploadCache() {
        int requiredFloats = boneCount * FLOATS_PER_BONE;
        if (uploadCache != null && uploadCache.capacity() >= requiredFloats) {
            return;
        }
        if (uploadCache != null) {
            MemoryUtil.memFree(uploadCache);
        }
        uploadCache = MemoryUtil.memAllocFloat(requiredFloats);
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        uploadRing.close();
        bufferId = 0;
        textureId = 0;
        if (uploadCache != null) {
            MemoryUtil.memFree(uploadCache);
            uploadCache = null;
        }
        closed = true;
    }
}
