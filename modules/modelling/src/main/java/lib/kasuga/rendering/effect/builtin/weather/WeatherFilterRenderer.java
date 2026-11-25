package lib.kasuga.rendering.effect.builtin.weather;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.weather.WeatherFilterBuffer;
import lib.kasuga.rendering.weather.WeatherFilterFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.GlStateBackup;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Owns the {@code kasuga_lib:weather/rain_filter} shader, top-down orthographic depth FBO,
 * and {@code GL_RGBA32F} OBB volume texture used during {@code LevelRenderer#renderSnowAndRain}.
 */
final class WeatherFilterRenderer {
    static final int DEPTH_SIZE = 512;
    static final float HORIZONTAL_RADIUS = 12.0f;
    static final float BOTTOM_REL_Y = -24.0f;
    static final float TOP_REL_Y = 96.0f;
    static final float DEPTH_BIAS = 0.05f;

    private final RenderShaderHandle shader;
    private @Nullable WeatherDepthBuffers depthBuffers;

    private int depthFramebuffer;
    private int depthTexture;
    private int fallbackDepthTexture;
    private int volumeTexture;
    private int volumeCapacity;
    private @Nullable ByteBuffer volumeData;

    WeatherFilterRenderer(RenderPipelineRegistrar registrar) {
        this.shader = registrar.shader(RenderShaderDescriptor.builder(
                WeatherFilters.id("rain_filter"),
                DefaultVertexFormat.PARTICLE
        ).resource().build()).handle();
    }

    boolean isShaderReady() {
        return shader.isReady() && shader.get() != null;
    }

    @Nullable
    ShaderInstance prepareAndBind(
            ClientLevel level,
            double camX,
            double camY,
            double camZ,
            float partialTick,
            List<WeatherOccluder> occluders,
            List<WeatherFilterFrame> nearbyVolumes
    ) {
        ShaderInstance instance = shader.get();
        if (instance == null) {
            return null;
        }
        Vec3 camera = new Vec3(camX, camY, camZ);
        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        double minWorldY = camY + BOTTOM_REL_Y;
        double maxWorldY = camY + TOP_REL_Y;

        boolean hasDepthOccluders = false;
        for (WeatherOccluder occluder : occluders) {
            if (occluder.hasNearbyOccluders(level, camera, cameraEntity, HORIZONTAL_RADIUS, minWorldY, maxWorldY, partialTick)) {
                hasDepthOccluders = true;
                break;
            }
        }

        if (!hasDepthOccluders && nearbyVolumes.isEmpty()) {
            return null;
        }

        ensureFallbackTextures();

        if (hasDepthOccluders) {
            renderDepthMap(level, camera, cameraEntity, partialTick, minWorldY, maxWorldY, occluders);
        }

        int uploadedVolumes = uploadVolumes(nearbyVolumes, camX, camY, camZ);

        instance.setSampler("RainDepthSampler", hasDepthOccluders ? depthTexture : fallbackDepthTexture);
        instance.setSampler("VolumeSampler", volumeTexture);
        instance.safeGetUniform("RainDepthEnabled").set(hasDepthOccluders ? 1 : 0);
        instance.safeGetUniform("RainDepthParams").set(
                1.0f / (2.0f * HORIZONTAL_RADIUS),
                BOTTOM_REL_Y,
                TOP_REL_Y - BOTTOM_REL_Y,
                DEPTH_BIAS
        );
        instance.safeGetUniform("VolumeCount").set(uploadedVolumes);
        return instance;
    }

    private void renderDepthMap(
            ClientLevel level,
            Vec3 camera,
            @Nullable Entity cameraEntity,
            float partialTick,
            double minWorldY,
            double maxWorldY,
            List<WeatherOccluder> occluders
    ) {
        ensureDepthFramebuffer();
        if (depthBuffers == null) {
            depthBuffers = new WeatherDepthBuffers();
        }
        depthBuffers.begin(depthFramebuffer, DEPTH_SIZE);

        Matrix4f orthoProj = buildTopDownOrthoMatrix();
        Matrix4f orthoView = new Matrix4f().identity();
        WeatherDepthPassContext context = new WeatherDepthPassContext(
                level,
                camera,
                cameraEntity,
                partialTick,
                HORIZONTAL_RADIUS,
                minWorldY,
                maxWorldY,
                new PoseStack(),
                depthBuffers,
                orthoView,
                orthoProj
        );

        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);

        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        ShaderInstance prevShader = RenderSystem.getShader();

        BufferUploader.reset();
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(orthoProj, VertexSorting.ORTHOGRAPHIC_Z);

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFramebuffer);
            GL11.glViewport(0, 0, DEPTH_SIZE, DEPTH_SIZE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            for (WeatherOccluder occluder : occluders) {
                occluder.renderDepth(context);
            }
            depthBuffers.flush();
        } finally {
            BufferUploader.reset();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(prevProj, prevSorting);
            RenderSystem.restoreGlState(backup);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            RenderSystem.viewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            if (prevShader != null) {
                RenderSystem.setShader(() -> prevShader);
            }
        }
    }

    private static Matrix4f buildTopDownOrthoMatrix() {
        float invR = 1.0f / HORIZONTAL_RADIUS;
        float span = TOP_REL_Y - BOTTOM_REL_Y;
        float a = -2.0f / span;
        float b = (TOP_REL_Y + BOTTOM_REL_Y) / span;
        return new Matrix4f(
                invR, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, a, 0.0f,
                0.0f, invR, 0.0f, 0.0f,
                0.0f, 0.0f, b, 1.0f
        );
    }

    private int uploadVolumes(List<WeatherFilterFrame> frames, double eyeX, double eyeY, double eyeZ) {
        int count = frames.size();
        if (count == 0) {
            ensureVolumeTexture(1);
            return 0;
        }
        ensureVolumeTexture(count);
        int bytes = Math.multiplyExact(count, WeatherFilterBuffer.BYTES_PER_VOLUME);
        if (volumeData == null || volumeData.capacity() < bytes) {
            ByteBuffer replacement = MemoryUtil.memAlloc(bytes);
            if (volumeData != null) {
                MemoryUtil.memFree(volumeData);
            }
            volumeData = replacement;
        }
        volumeData.clear();
        for (WeatherFilterFrame frame : frames) {
            WeatherFilterBuffer.write(volumeData, frame, eyeX, eyeY, eyeZ);
        }
        volumeData.flip();

        int prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevUnpackAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int prevSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, volumeTexture);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    WeatherFilterBuffer.TEXELS_PER_VOLUME,
                    count,
                    GL11.GL_RGBA,
                    GL11.GL_FLOAT,
                    volumeData
            );
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevSkipRows);
            GL13.glActiveTexture(prevUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
        return count;
    }

    private void ensureDepthFramebuffer() {
        if (depthFramebuffer != 0 && depthTexture != 0) {
            return;
        }
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            depthTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            FloatBuffer border = MemoryUtil.memAllocFloat(4);
            try {
                border.put(1.0f).put(1.0f).put(1.0f).put(1.0f).flip();
                GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, border);
            } finally {
                MemoryUtil.memFree(border);
            }
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL30.GL_DEPTH_COMPONENT32F,
                    DEPTH_SIZE,
                    DEPTH_SIZE,
                    0,
                    GL11.GL_DEPTH_COMPONENT,
                    GL11.GL_FLOAT,
                    (ByteBuffer) null
            );

            depthFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFramebuffer);
            GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D,
                    depthTexture,
                    0
            );
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                releaseDepthTarget();
                throw new IllegalStateException("Weather depth framebuffer incomplete: " + status);
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    private void ensureFallbackTextures() {
        if (fallbackDepthTexture == 0) {
            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            FloatBuffer one = MemoryUtil.memAllocFloat(1);
            try {
                one.put(1.0f).flip();
                fallbackDepthTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackDepthTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        GL30.GL_DEPTH_COMPONENT32F,
                        1,
                        1,
                        0,
                        GL11.GL_DEPTH_COMPONENT,
                        GL11.GL_FLOAT,
                        one
                );
            } finally {
                MemoryUtil.memFree(one);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            }
        }
        if (volumeTexture == 0) {
            ensureVolumeTexture(16);
        }
    }

    private void ensureVolumeTexture(int requiredCount) {
        int desired = Math.max(16, Integer.highestOneBit(Math.max(1, requiredCount - 1)) << 1);
        if (volumeTexture != 0 && volumeCapacity >= desired) {
            return;
        }
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (volumeTexture != 0) {
                GL11.glDeleteTextures(volumeTexture);
            }
            volumeTexture = GL11.glGenTextures();
            volumeCapacity = desired;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, volumeTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL30.GL_RGBA32F,
                    WeatherFilterBuffer.TEXELS_PER_VOLUME,
                    volumeCapacity,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_FLOAT,
                    (ByteBuffer) null
            );
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    private void releaseDepthTarget() {
        if (depthFramebuffer != 0) {
            GL30.glDeleteFramebuffers(depthFramebuffer);
            depthFramebuffer = 0;
        }
        if (depthTexture != 0) {
            GL11.glDeleteTextures(depthTexture);
            depthTexture = 0;
        }
    }

    void release() {
        releaseDepthTarget();
        if (fallbackDepthTexture != 0) {
            GL11.glDeleteTextures(fallbackDepthTexture);
            fallbackDepthTexture = 0;
        }
        if (volumeTexture != 0) {
            GL11.glDeleteTextures(volumeTexture);
            volumeTexture = 0;
        }
        volumeCapacity = 0;
        if (volumeData != null) {
            MemoryUtil.memFree(volumeData);
            volumeData = null;
        }
        if (depthBuffers != null) {
            depthBuffers.close();
            depthBuffers = null;
        }
    }
}
