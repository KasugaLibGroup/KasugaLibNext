package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.*;
import lib.kasuga.rendering.models.mc.backend.context.CpuSkinningContext;
import lib.kasuga.rendering.models.mc.backend.context.GLContext;
import lib.kasuga.rendering.models.mc.backend.context.IrisGpuSkinningContext;
import lib.kasuga.rendering.models.mc.backend.context.VanillaGpuSkinningContext;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.transform.BoneTransformTBO;
import lib.kasuga.rendering.models.mc.backend.transform.TransformFeedbackProgram;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IrisVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.vbuffer.VanillaVertexBuffer;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** GPU resources for one model, split into independently stateful alpha passes. */
public class BackendInstance implements AutoCloseable {

    public static final VertexFormat VANILLA_FORMAT = RenderState.UML_VERTEX_FORMAT;
    public static final VertexFormat IRIS_FORMAT = DefaultVertexFormat.NEW_ENTITY;

    public static final ResourceLocation SKINNING_PROGRAM_LOCATION =
            ResourceLocation.parse("kasuga_lib:shaders/ksg_skinning.transform.glsl");

    @Getter
    private final ModelInstance model;

    private final ExecutorService executor;
    private final boolean cpuSkinning;
    private final EnumMap<ModelRenderPass, RenderPart> parts = new EnumMap<>(ModelRenderPass.class);
    @Nullable
    private final BoneTransformTBO tbo;
    private final Matrix4f matrixCache = new Matrix4f();
    private Object renderFrameToken;

    void renderInFrame(Object token) { renderFrameToken = token; }

    private void updateData(FlatModelData data) {
        if (renderFrameToken == null) {
            if (model.checkForUpdate()) model.update();
        } else {
            model.prepareRenderFrame(renderFrameToken);
        }
        data.updateModel(false);
        if (tbo != null) tbo.updateForVersion();
    }

    public BackendInstance(ModelInstance instance, ExecutorService executor, boolean cpuSkinning) {
        this.model = instance;
        this.executor = executor;
        this.cpuSkinning = cpuSkinning;

        Map<VertexFormatElement, Integer> bufOffsets =
                FlatModelData.genVertexFormat(RenderState.UML_VERTEX_FORMAT);
        this.tbo = cpuSkinning ? null : new BoneTransformTBO(instance.getSkeletonInstance());

        for (ModelRenderPass pass : ModelRenderPass.values()) {
            FlatModelData data = new FlatModelData(instance,
                    RenderState.UML_VERTEX_FORMAT.getVertexSize(),
                    bufOffsets, null, 1.0f, true, cpuSkinning,
                    OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, pass);
            if (data.getVertexCount() == 0) {
                try {
                    data.close();
                } catch (Exception ignored) {
                    // Empty passes have no GPU resources that need recovery.
                }
                continue;
            }
            parts.put(pass, new RenderPart(data, bufOffsets));
        }
    }

    public boolean hasPass(ModelRenderPass pass) {
        return parts.containsKey(pass);
    }

    @Nullable
    public FlatModelData getData(ModelRenderPass pass) {
        RenderPart part = parts.get(pass);
        return part == null ? null : part.data;
    }

    /** Compatibility accessor for callers that only need any model payload. */
    @Nullable
    public FlatModelData getData() {
        for (ModelRenderPass pass : ModelRenderPass.values()) {
            FlatModelData data = getData(pass);
            if (data != null) return data;
        }
        return null;
    }

    public VertexFormat.Mode getMeshMode(ModelRenderPass pass) {
        FlatModelData data = getData(pass);
        return data == null ? VertexFormat.Mode.QUADS : data.getMcMeshMode();
    }

    /** Compatibility accessor for the first available pass. */
    public VertexFormat.Mode getMeshMode() {
        FlatModelData data = getData();
        return data == null ? VertexFormat.Mode.QUADS : data.getMcMeshMode();
    }

    public void updateLightData(int light, int overlay, float brightness) {
        for (RenderPart part : parts.values()) {
            part.data.setLight(light);
            part.data.setOverlay(overlay);
            part.data.setBrightness(brightness);
        }
    }

    boolean prepareForGlobalBatch(ModelRenderPass pass) {
        RenderPart part = parts.get(pass);
        if (part == null) return false;
        updateData(part.data);
        part.lastUsedBuffer = null;
        return true;
    }

    boolean usesCpuSkinning() {
        return cpuSkinning;
    }

    @Nullable
    BoneTransformTBO getBoneTransformTBO() {
        return tbo;
    }

    void clearBatchDirtyVertices(ModelRenderPass pass) {
        FlatModelData data = getData(pass);
        if (data != null) data.getDirtyVertices().clear();
    }

    protected void drawBuffer(ModelRenderPass pass, PoseStack.Pose pose, RenderType renderType,
                              Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                              float emissiveStrength, float ambientLightEnhancement) {
        drawBuffer(pass, pose, renderType, modelViewMatrix, projectionMatrix,
                emissiveStrength, ambientLightEnhancement, 0);
    }

    protected void drawBuffer(ModelRenderPass pass, PoseStack.Pose pose, RenderType renderType,
                              Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                              float emissiveStrength, float ambientLightEnhancement,
                              int oitMode) {
        if (!prepareDraw(pass)) return;
        drawPreparedBuffer(pass, pose, renderType, modelViewMatrix, projectionMatrix,
                emissiveStrength, ambientLightEnhancement, oitMode);
    }

    /** Upload once before replaying immutable geometry through the peel layers. */
    boolean prepareDraw(ModelRenderPass pass) {
        RenderPart part = parts.get(pass);
        if (part == null) return false;
        GLContext context = part.getContext();
        IVertexBuffer buffer = part.getBuffer();
        if (context == null || buffer == null) return false;
        updateData(part.data);
        buffer.updateGpuBuffer(part.data.getDirtyVertices(), part.lastUsedBuffer != buffer);
        part.lastUsedBuffer = buffer;
        part.data.getDirtyVertices().clear();
        context.dispatchSkinning(part.data.getVertexCount());
        return true;
    }

    void drawPreparedBuffer(ModelRenderPass pass, PoseStack.Pose pose, RenderType renderType,
                            Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                            float emissiveStrength, float ambientLightEnhancement, int oitMode) {
        RenderPart part = parts.get(pass);
        if (part == null) return;

        GLContext context = part.getContext();
        IVertexBuffer buffer = part.getBuffer();
        if (context == null || buffer == null) return;

        ShaderInstance shader = null;
        try {
            if (isIrisEnabled()) {
                modelViewMatrix = matrixCache.set(modelViewMatrix).mul(pose.pose());
            }
            shader = context.enter(renderType, part.data.getMcMeshMode(),
                    modelViewMatrix, projectionMatrix,
                    s -> setupShader(s, pose, emissiveStrength, ambientLightEnhancement, pass, oitMode)
            ).get();
            // Iris shader programs bind their own gbuffer and may apply pack-
            // supplied blend overrides in ShaderInstance.apply(). OIT must
            // reclaim its attachment after that point and immediately before
            // the draw call.
            OitRenderer.rebindAfterShaderApply(oitMode);
            // Every context's enter() sets default/custom uniforms, applies
            // the shader and binds the VAO (including Iris' skinned attributes).
            // Reapplying here repeats that work and can undo the OIT rebind.
            try { buffer.getVertexBuffer().draw(); }
            finally {
                buffer.markSubmitted();
                if (tbo != null) tbo.markSubmitted();
            }
        } finally {
            context.exit(shader, renderType);
        }
    }

    public static boolean isIrisInstalled() {
        return IrisCompat.isIrisPresent();
    }

    public static boolean isIrisEnabled() {
        return IrisCompat.isUsingShaderPack();
    }

    public static VertexFormat getFormat() {
        return getFormat(isIrisEnabled());
    }

    public static VertexFormat getFormat(boolean iris) {
        return iris ? IrisCompat.getIrisFormat(IRIS_FORMAT, iris) : VANILLA_FORMAT;
    }

    public void setupShader(ShaderInstance shader, PoseStack.Pose pose, float emissiveStrength) {
        setupShader(shader, pose, emissiveStrength, model.getAmbientLightEnhancement(),
                ModelRenderPass.OPAQUE);
    }

    public void setupShader(ShaderInstance shader, PoseStack.Pose pose, float emissiveStrength,
                            float ambientLightEnhancement) {
        setupShader(shader, pose, emissiveStrength, ambientLightEnhancement,
                ModelRenderPass.OPAQUE);
    }

    public void setupShader(ShaderInstance shader, PoseStack.Pose pose, float emissiveStrength,
                            float ambientLightEnhancement, ModelRenderPass pass) {
        setupShader(shader, pose, emissiveStrength, ambientLightEnhancement, pass, 0);
    }

    public void setupShader(ShaderInstance shader, PoseStack.Pose pose, float emissiveStrength,
                            float ambientLightEnhancement, ModelRenderPass pass, int oitMode) {
        if (!(shader instanceof KasugaShaderInstance kasugaShader)) return;
        FlatModelData data = getData(pass);
        if (data == null) return;
        kasugaShader.setCurrentPose(pose);
        kasugaShader.setEmissiveStrength(emissiveStrength);
        kasugaShader.setAmbientLightEnhancement(ambientLightEnhancement);
        kasugaShader.setLightData(data.getBrightness(), data.getLightmap(), data.getOverlay());
        kasugaShader.setAlphaMode(pass.shaderAlphaMode());
        kasugaShader.setOitMode(oitMode);
        kasugaShader.setGpuSkinningState(!cpuSkinning, tbo != null ? tbo.getTextureId() : 0);
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (RenderPart part : parts.values()) {
            try {
                part.close();
            } catch (Exception exception) {
                failure = failure == null ? exception : failure;
            }
        }
        parts.clear();
        if (tbo != null) {
            try {
                tbo.close();
            } catch (Exception exception) {
                failure = failure == null ? exception : failure;
            }
        }
        if (failure != null) throw failure;
    }

    private final class RenderPart implements AutoCloseable {
        private final FlatModelData data;
        private IVertexBuffer lastUsedBuffer;
        @Nullable
        private final CpuSkinningContext cpuContext;
        @Nullable
        private final IrisGpuSkinningContext irisContext;
        @Nullable
        private final VanillaGpuSkinningContext vanillaContext;
        @Nullable
        private final IrisVertexBuffer irisBuffer;
        private final VanillaVertexBuffer vanillaBuffer;
        @Nullable
        private final TransformFeedbackProgram program;

        private RenderPart(FlatModelData data, Map<VertexFormatElement, Integer> bufOffsets) {
            this.data = data;
            this.vanillaBuffer = new VanillaVertexBuffer(data, getFormat(false), 64);
            if (cpuSkinning) {
                this.program = null;
                this.irisContext = null;
                this.vanillaContext = null;
                this.irisBuffer = isIrisInstalled()
                        ? new IrisVertexBuffer(data, getFormat(true), 10000, 64, executor) : null;
                this.cpuContext = new CpuSkinningContext(this::getVertexBuffer, null);
                return;
            }

            this.cpuContext = null;
            if (isIrisInstalled()) {
                this.program = new TransformFeedbackProgram(SKINNING_PROGRAM_LOCATION,
                        data::getBuffer, bufOffsets, data.getVertexSize());
                this.irisContext = new IrisGpuSkinningContext(getFormat(true),
                        this::getVertexBuffer, null, tbo, program);
                this.irisBuffer = new IrisVertexBuffer(data, getFormat(true),
                        10000, 64, executor);
            } else {
                this.program = null;
                this.irisContext = null;
                this.irisBuffer = null;
            }
            this.vanillaContext = new VanillaGpuSkinningContext(tbo,
                    this::getVertexBuffer, null);
        }

        private VertexBuffer getVertexBuffer() {
            return getBuffer().getVertexBuffer();
        }

        private IVertexBuffer getBuffer() {
            return isIrisEnabled() && irisBuffer != null ? irisBuffer : vanillaBuffer;
        }

        @Nullable
        private GLContext getContext() {
            if (cpuSkinning) return cpuContext;
            return isIrisEnabled() && irisContext != null ? irisContext : vanillaContext;
        }

        @Override
        public void close() throws Exception {
            Exception failure = null;
            try {
                data.close();
            } catch (Exception exception) {
                failure = exception;
            }
            try {
                vanillaBuffer.close();
            } catch (Exception exception) {
                failure = failure == null ? exception : failure;
            }
            if (irisBuffer != null) {
                try {
                    irisBuffer.close();
                } catch (Exception exception) {
                    failure = failure == null ? exception : failure;
                }
            }
            if (program != null) {
                try {
                    program.close();
                } catch (Exception exception) {
                    failure = failure == null ? exception : failure;
                }
            }
            if (failure != null) throw failure;
        }
    }
}
