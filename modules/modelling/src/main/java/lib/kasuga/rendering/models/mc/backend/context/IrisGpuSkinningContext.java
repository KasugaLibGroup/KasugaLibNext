package lib.kasuga.rendering.models.mc.backend.context;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.transform.BoneTransformTBO;
import lib.kasuga.rendering.models.mc.backend.transform.TransformFeedbackProgram;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class IrisGpuSkinningContext implements GLContext {

    private VertexBuffer currentBuffer;

    @Getter
    private final Supplier<VertexBuffer> bufferSupplier;

    @Getter
    @Nullable
    private final Consumer<ShaderInstance> beforeShaderApply;

    @Getter
    private final BoneTransformTBO boneTransformTBO;

    @Getter
    private final TransformFeedbackProgram program;

    @Getter
    private int overriddenPositionLocation;

    @Getter
    private final VertexFormat format;

    public IrisGpuSkinningContext(@NotNull VertexFormat format,
                                  @NotNull Supplier<VertexBuffer> bufferSupplier,
                                  @Nullable Consumer<ShaderInstance> beforeShaderApply,
                                  BoneTransformTBO boneTransformTBO,
                                  TransformFeedbackProgram program) {
        this.boneTransformTBO = boneTransformTBO;
        this.bufferSupplier = bufferSupplier;
        this.overriddenPositionLocation = 0;
        this.beforeShaderApply = beforeShaderApply;
        this.program = program;
        this.format = format;
        currentBuffer = null;
    }

    public int getBoneTransformTextureId() {
        return boneTransformTBO.getTextureId();
    }

    @Override
    public Supplier<ShaderInstance> enter(RenderType renderType, VertexFormat.Mode mode, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Consumer<ShaderInstance> beforeShaderApply) {
        renderType.setupRenderState();
        BufferUploader.reset();

        VertexBuffer irisBuffer = bufferSupplier.get();
        Objects.requireNonNull(irisBuffer);
        irisBuffer.bind();
        currentBuffer = irisBuffer;

        ShaderInstance currentShader = RenderSystem.getShader();
        setupShaderState(currentShader, mode, beforeShaderApply, modelViewMatrix, projectionMatrix,
                Minecraft.getInstance().getWindow());
        overriddenPositionLocation = overrideIrisGpuSkinnedPositionAttribute(currentShader);
        return () -> currentShader;
    }

    public void dispatchSkinning(int numVertices) {
        if (program == null || !program.isValid()
                || !program.needsDispatch(boneTransformTBO.getSkeletonVersion(), numVertices)) return;

        RenderSystem.assertOnRenderThread();
        program.ensureSkinningObjects(numVertices);
        program.uploadSkinningSourceIfNeeded();
        int sourceVao = program.getSourceVaoId();
        if (sourceVao == 0 || program.getOutputBufferId() == 0) return;
        if (!program.isValid()) return;

        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        int previousTextureBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        boolean previousRasterDiscard = GL11.glGetBoolean(GL30.GL_RASTERIZER_DISCARD);
        int previousFeedbackBuffer = GL30.glGetIntegeri(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, 0);
        int previousGenericFeedback = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);

        try {
            program.bind(GL13.GL_TEXTURE7, getBoneTransformTextureId());

            GL30.glBindVertexArray(sourceVao);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, program.getOutputBufferId());
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBeginTransformFeedback(GL11.GL_POINTS);
            program.markSourceSubmitted();
            boneTransformTBO.markSubmitted();
            GL11.glDrawArrays(GL11.GL_POINTS, 0, numVertices);
            GL30.glEndTransformFeedback();
            program.dispatched(boneTransformTBO.getSkeletonVersion(), numVertices);
        } finally {
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, previousFeedbackBuffer);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, previousGenericFeedback);
            GL30.glBindVertexArray(previousVao);
            RenderSystem.activeTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTextureBinding);
            RenderSystem.activeTexture(previousActiveTexture);
            GlStateManager._glBindBuffer(GL30.GL_ARRAY_BUFFER, previousArrayBuffer);
            program.unbind(previousProgram);
            if (previousRasterDiscard) {
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            } else {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            }
        }
    }

    @Override
    public void exit(ShaderInstance shader, RenderType renderType) {
        try {
            Objects.requireNonNull(currentBuffer, "VertexBuffer not bind.");
            restoreIrisStaticAttributes(this.overriddenPositionLocation);
            VertexBuffer.unbind();
            BufferUploader.reset();
            renderType.clearRenderState();
        } finally {
            if (shader != null) shader.clear();
        }
    }

    protected int overrideIrisGpuSkinnedPositionAttribute(ShaderInstance shader) {
        int positionLocation = getFirstAttributeLocation(shader,
                "iris_Position",
                "Position",
                "vaPosition",
                "a_Position");
        if (positionLocation < 0) {
            positionLocation = 0;
        }
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, program.getOutputBufferId());
        GL20.glEnableVertexAttribArray(positionLocation);
        GL20.glVertexAttribPointer(positionLocation, 3, GL11.GL_FLOAT, false, TransformFeedbackProgram.STRIDE, 0L);
        return positionLocation;
    }

    protected int getFirstAttributeLocation(ShaderInstance shader, String... names) {
        int programId = shader.getId();
        for (String name : names) {
            int location = GL20.glGetAttribLocation(programId, name);
            if (location >= 0) {
                return location;
            }
        }
        return -1;
    }

    protected void restoreIrisStaticAttributes(int overriddenPositionLocation) {
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, ((AccessorVertexBuffer)currentBuffer).getVertexBufferId());
        format.setupBufferState();
        if (overriddenPositionLocation > 0) {
            GL20.glDisableVertexAttribArray(overriddenPositionLocation);
        }
    }

    @Override
    public boolean isGpuSkinning() {
        return true;
    }
}
