package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.post.FullscreenPassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/** Render implementation for {@link OITVisualTestScene}; content-testing only. */
final class OITVisualTestRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float SCENE_DISTANCE = 9.0f;
    private static final float TOP_Y = 1.55f;
    private static final float BOTTOM_Y = -1.25f;
    private static final float A_X = -5.0f;
    private static final float B_X = -1.8f;
    private static final float C_X = 1.9f;
    private static final float D_X = -5.0f;
    private static final float E_X = -1.1f;
    private static final float F_X = 4.0f;
    private static final int TRANSPARENT_LAYER_COUNT = 22;

    private static final int OIT_NORMAL = 0;
    private static final int OIT_ACCUMULATION = 1;
    private static final int OIT_REVEALAGE = 2;
    private static final int ALPHA_OPAQUE = 0;
    private static final int ALPHA_MASK = 1;
    private static final int ALPHA_BLEND = 2;

    static volatile ShaderInstance GEOMETRY_SHADER;
    static volatile ShaderInstance COMPOSITE_SHADER;

    private static final RenderStateShard.ShaderStateShard VISUAL_SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> GEOMETRY_SHADER);

    private static RenderType opaqueRenderType;
    private static RenderType maskRenderType;
    private static RenderType sortedRenderType;
    private static RenderType accumulationRenderType;
    private static RenderType revealageRenderType;
    private static OitTarget target;
    private static boolean oitDisabled;
    private static String lastOitFailure;

    private OITVisualTestRenderer() {
    }

    static void reset() {
        oitDisabled = false;
        lastOitFailure = null;
    }

    static void renderOpaque(WorldRenderPipelineContext context) {
        if (!OITVisualTestScene.enabled()) {
            releaseTarget();
            return;
        }
        if (!ensureRenderTypes()) return;
        drawGeometry(context, opaqueRenderType, ALPHA_OPAQUE, OIT_NORMAL,
                OITVisualTestRenderer::writeOpaqueGeometry);
    }

    static void renderMask(WorldRenderPipelineContext context) {
        if (!OITVisualTestScene.enabled() || !ensureRenderTypes()) return;
        drawGeometry(context, maskRenderType, ALPHA_MASK, OIT_NORMAL,
                OITVisualTestRenderer::writeMaskGeometry);
    }

    static void renderTranslucent(WorldRenderPipelineContext context) {
        if (!OITVisualTestScene.enabled()) {
            releaseTarget();
            return;
        }
        if (!ensureRenderTypes()) {
            OITVisualTestScene.updateReport(OITVisualTestScene.RenderReport.pending());
            return;
        }

        if (OITVisualTestScene.renderMode() == OITVisualTestScene.RenderMode.WEIGHTED_OIT
                && !oitDisabled && canUseOit()) {
            if (renderWeightedOit(context)) return;
        }

        String failure = OITVisualTestScene.renderMode() == OITVisualTestScene.RenderMode.WEIGHTED_OIT
                ? (lastOitFailure == null ? oitUnavailableReason() : lastOitFailure)
                : null;
        renderSorted(context, failure);
    }

    private static boolean renderWeightedOit(WorldRenderPipelineContext context) {
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        if (!mainTarget.useDepth || mainTarget.frameBufferId < 0) {
            lastOitFailure = "main depth target unavailable";
            return false;
        }

        RenderSystem.assertOnRenderThread();
        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        boolean resolveAttempted = false;
        try {
            if (target == null) target = new OitTarget();
            target.prepare(mainTarget);

            target.bindAccumulation();
            drawGeometry(context, accumulationRenderType, ALPHA_BLEND, OIT_ACCUMULATION,
                    OITVisualTestRenderer::writeTransparentGeometry);

            target.bindRevealage();
            drawGeometry(context, revealageRenderType, ALPHA_BLEND, OIT_REVEALAGE,
                    OITVisualTestRenderer::writeTransparentGeometry);

            OITVisualTestScene.BufferView view = OITVisualTestScene.bufferView();
            if (view == OITVisualTestScene.BufferView.FINAL) {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            } else {
                // Debug views are opaque diagnostic images, not source-over overlays.
                RenderSystem.disableBlend();
            }

            OitTarget resolvedTarget = target;
            resolveAttempted = true;
            FullscreenPassRenderer.draw(mainTarget, COMPOSITE_SHADER, false, shader -> {
                shader.setSampler("AccumulationSampler", resolvedTarget.accumulationTextureId());
                shader.setSampler("RevealageSampler", resolvedTarget.revealageTextureId());
                shader.setSampler("DepthSampler", resolvedTarget.depthTextureId());
                setUniform(shader, "DebugView", view.ordinal());
                setUniform(shader, "NearPlane", 0.05f);
                setUniform(shader, "FarPlane", 256.0f);
            });

            OITVisualTestScene.updateReport(new OITVisualTestScene.RenderReport(
                    "WEIGHTED_OIT", "COMPLETE", "RGBA16F", "R16F", "copied",
                    mainTarget.width, mainTarget.height, true, null));
            return true;
        } catch (RuntimeException exception) {
            oitDisabled = true;
            lastOitFailure = conciseFailure(exception);
            releaseTarget();
            LOGGER.warn("Disabling OIT visual test path after a rendering failure", exception);
            OITVisualTestScene.updateReport(new OITVisualTestScene.RenderReport(
                    resolveAttempted ? "OIT_FAILED" : "SORTED_FALLBACK",
                    "FAILED", "-", "-", "-", mainTarget.width, mainTarget.height,
                    false, lastOitFailure));
            // A failed resolve may already have touched the main target. Do not layer a
            // second sorted resolve over a partially written frame.
            return resolveAttempted;
        } finally {
            mainTarget.bindWrite(true);
            RenderSystem.restoreGlState(backup);
            mainTarget.bindWrite(true);
        }
    }

    private static void renderSorted(WorldRenderPipelineContext context, String failure) {
        drawGeometry(context, sortedRenderType, ALPHA_BLEND, OIT_NORMAL,
                OITVisualTestRenderer::writeTransparentGeometry);
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        String path = "SORTED_FALLBACK";
        if (OITVisualTestScene.renderMode() == OITVisualTestScene.RenderMode.SORTED_FALLBACK) {
            failure = null;
        }
        OITVisualTestScene.updateReport(new OITVisualTestScene.RenderReport(
                path, "not used", "-", "-", "scene depth", mainTarget.width,
                mainTarget.height, false, failure));
    }

    private static boolean canUseOit() {
        if (GEOMETRY_SHADER == null || COMPOSITE_SHADER == null
                || RenderState.OIT_TARGET == null
                || RenderState.OIT_REVEALAGE_TRANSPARENCY == null
                || BackendInstance.isIrisEnabled()) {
            return false;
        }
        try {
            return GL.getCapabilities().OpenGL30
                    && GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS) >= 2
                    && GL11.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS) >= 2;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String oitUnavailableReason() {
        if (BackendInstance.isIrisEnabled()) return "Iris shaderpack active";
        if (GEOMETRY_SHADER == null) return "visual geometry shader unavailable";
        if (COMPOSITE_SHADER == null) return "visual composite shader unavailable";
        try {
            if (!GL.getCapabilities().OpenGL30) return "OpenGL 3.0 unavailable";
            if (GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS) < 2) return "one color attachment only";
            if (GL11.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS) < 2) return "one draw buffer only";
        } catch (RuntimeException ignored) {
            return "no current OpenGL context";
        }
        return "OIT disabled after a previous failure";
    }

    private static boolean ensureRenderTypes() {
        if (GEOMETRY_SHADER == null) return false;
        if (opaqueRenderType != null) return true;
        opaqueRenderType = createGeometryRenderType(
                "kasuga_lib:oit_visual_opaque", RenderStateShard.NO_TRANSPARENCY,
                RenderStateShard.MAIN_TARGET, true, false);
        maskRenderType = createGeometryRenderType(
                "kasuga_lib:oit_visual_mask", RenderStateShard.NO_TRANSPARENCY,
                RenderStateShard.MAIN_TARGET, true, false);
        sortedRenderType = createGeometryRenderType(
                "kasuga_lib:oit_visual_sorted", RenderStateShard.TRANSLUCENT_TRANSPARENCY,
                RenderStateShard.MAIN_TARGET, false, false);
        accumulationRenderType = createGeometryRenderType(
                "kasuga_lib:oit_visual_accumulation", RenderState.OIT_ACCUMULATION_TRANSPARENCY,
                RenderState.OIT_TARGET, false, false);
        revealageRenderType = createGeometryRenderType(
                "kasuga_lib:oit_visual_revealage", RenderState.OIT_REVEALAGE_TRANSPARENCY,
                RenderState.OIT_TARGET, false, false);
        return true;
    }

    private static RenderType createGeometryRenderType(
            String name, RenderStateShard.TransparencyStateShard transparency,
            RenderStateShard.OutputStateShard output, boolean depthWrite, boolean sortOnUpload) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
                64 * 1024, false, sortOnUpload,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderStateShard.NO_TEXTURE)
                        .setShaderState(VISUAL_SHADER_STATE)
                        .setTransparencyState(transparency)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setLayeringState(RenderStateShard.NO_LAYERING)
                        .setOutputState(output)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(depthWrite
                                ? RenderStateShard.COLOR_DEPTH_WRITE : RenderStateShard.COLOR_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false));
    }

    private static void drawGeometry(WorldRenderPipelineContext context, RenderType renderType,
                                     int alphaMode, int oitMode, GeometryWriter writer) {
        RenderSystem.assertOnRenderThread();
        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        try {
            pushSceneTransform(context, poseStack);
            VertexConsumer consumer = context.bufferSource().getBuffer(renderType);
            writer.write(consumer, poseStack.last().pose());
            setUniform(GEOMETRY_SHADER, "AlphaMode", alphaMode);
            setUniform(GEOMETRY_SHADER, "AlphaCutoff", 0.5f);
            setUniform(GEOMETRY_SHADER, "OitMode", oitMode);
            context.bufferSource().endBatch(renderType);
        } finally {
            poseStack.popPose();
            RenderSystem.restoreGlState(backup);
        }
    }

    private static void pushSceneTransform(WorldRenderPipelineContext context, PoseStack poseStack) {
        Quaternionf rotation = new Quaternionf(context.camera().rotation());
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(rotation);
        var camera = context.camera().getPosition();
        var anchor = camera.add(forward.x * SCENE_DISTANCE,
                forward.y * SCENE_DISTANCE, forward.z * SCENE_DISTANCE);
        poseStack.translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z);
        poseStack.mulPose(rotation);
    }

    private static void writeOpaqueGeometry(VertexConsumer consumer, Matrix4f pose) {
        writeCube(consumer, pose, A_X, TOP_Y, 0.05f,
                0.56f, 0.58f, 0.34f, 0.78f, 0.82f, 0.86f);
        writeIrregularWall(consumer, pose);
    }

    private static void writeMaskGeometry(VertexConsumer consumer, Matrix4f pose) {
        float left = B_X - 1.02f;
        float right = B_X + 1.02f;
        float bottom = TOP_Y - 0.66f;
        float top = TOP_Y + 0.66f;
        vertex(consumer, pose, left, bottom, 0.26f, 1.0f, 0.82f, 0.12f, 0.0f);
        vertex(consumer, pose, right, bottom, 0.26f, 1.0f, 0.82f, 0.12f, 1.0f);
        vertex(consumer, pose, right, top, 0.26f, 1.0f, 0.82f, 0.12f, 1.0f);
        vertex(consumer, pose, left, top, 0.26f, 1.0f, 0.82f, 0.12f, 0.0f);
    }

    private static void writeTransparentGeometry(VertexConsumer consumer, Matrix4f pose) {
        int[] order = OITVisualTestScene.orderIndices(TRANSPARENT_LAYER_COUNT);
        for (int index : order) writeTransparentLayer(consumer, pose, index);
    }

    private static void writeTransparentLayer(VertexConsumer consumer, Matrix4f pose, int index) {
        switch (index) {
            case 0 -> quad(consumer, pose, A_X - 1.03f, TOP_Y - 0.75f,
                    A_X + 1.03f, TOP_Y + 0.75f, -0.25f, 0.95f, 0.10f, 0.08f, 0.50f);
            case 1 -> quad(consumer, pose, B_X - 1.08f, TOP_Y - 0.70f,
                    B_X + 1.08f, TOP_Y + 0.70f, -0.08f, 0.62f, 0.16f, 0.86f, 0.48f);
            case 2 -> crossingQuad(consumer, pose, C_X, TOP_Y, 1.35f, 0.66f,
                    0.30f, -0.30f, 0.96f, 0.08f, 0.08f, 0.55f);
            case 3 -> crossingQuad(consumer, pose, C_X, TOP_Y, 1.35f, 0.66f,
                    -0.30f, 0.30f, 0.08f, 0.26f, 0.98f, 0.55f);
            case 4 -> quad(consumer, pose, D_X - 1.07f, BOTTOM_Y - 0.69f,
                    D_X + 1.07f, BOTTOM_Y + 0.69f, 0.34f, 0.10f, 0.92f, 0.28f, 0.46f);
            case 5 -> quad(consumer, pose, D_X - 1.07f, BOTTOM_Y - 0.69f,
                    D_X + 1.07f, BOTTOM_Y + 0.69f, -0.34f, 0.95f, 0.10f, 0.20f, 0.46f);
            case 6 -> quad(consumer, pose, F_X - 1.25f, BOTTOM_Y - 0.66f,
                    F_X + 1.25f, BOTTOM_Y + 0.66f, -0.14f, 0.10f, 0.78f, 0.95f, 0.27f);
            case 7 -> crossingQuad(consumer, pose, F_X, BOTTOM_Y, 1.18f, 0.58f,
                    0.24f, -0.24f, 0.94f, 0.82f, 0.12f, 0.30f);
            case 8 -> crossingQuad(consumer, pose, F_X, BOTTOM_Y, 1.18f, 0.58f,
                    -0.24f, 0.24f, 0.98f, 0.20f, 0.82f, 0.30f);
            case 9 -> quad(consumer, pose, F_X - 1.20f, BOTTOM_Y - 0.62f,
                    F_X + 1.20f, BOTTOM_Y + 0.62f, 0.08f, 0.92f, 0.85f, 0.12f, 0.24f);
            default -> writePermutationQuad(consumer, pose, index - 10);
        }
    }

    private static void writePermutationQuad(VertexConsumer consumer, Matrix4f pose, int index) {
        float[] colors = {
                1.0f, 0.25f, 0.25f,
                0.25f, 1.0f, 0.30f,
                0.25f, 0.48f, 1.0f,
                1.0f, 0.90f, 0.20f,
                0.95f, 0.25f, 1.0f,
                0.15f, 0.95f, 1.0f,
                1.0f, 0.50f, 0.16f,
                0.46f, 0.20f, 1.0f,
                0.20f, 1.0f, 0.78f,
                0.95f, 0.35f, 0.62f,
                0.56f, 1.0f, 0.20f,
                0.20f, 0.72f, 1.0f
        };
        float angle = index * (float) Math.PI / 12.0f + 0.11f;
        float ux = (float) Math.cos(angle);
        float uy = (float) Math.sin(angle);
        float vx = -uy;
        float vy = ux;
        float centerX = E_X + (float) Math.cos(index * 2.1f) * 0.07f;
        float centerY = BOTTOM_Y + (float) Math.sin(index * 1.7f) * 0.07f;
        float halfWidth = 1.20f;
        float halfHeight = 0.68f;
        float z = ((index % 5) - 2) * 0.07f;
        float slope = (index & 1) == 0 ? 0.30f : -0.28f;
        float alpha = 0.22f + (index % 3) * 0.035f;
        int color = index * 3;
        orientedVertex(consumer, pose, centerX, centerY, ux, uy, vx, vy,
                -halfWidth, -halfHeight, z, slope, colors[color], colors[color + 1], colors[color + 2], alpha);
        orientedVertex(consumer, pose, centerX, centerY, ux, uy, vx, vy,
                halfWidth, -halfHeight, z, slope, colors[color], colors[color + 1], colors[color + 2], alpha);
        orientedVertex(consumer, pose, centerX, centerY, ux, uy, vx, vy,
                halfWidth, halfHeight, z, slope, colors[color], colors[color + 1], colors[color + 2], alpha);
        orientedVertex(consumer, pose, centerX, centerY, ux, uy, vx, vy,
                -halfWidth, halfHeight, z, slope, colors[color], colors[color + 1], colors[color + 2], alpha);
    }

    private static void writeIrregularWall(VertexConsumer consumer, Matrix4f pose) {
        float left = D_X - 1.08f;
        float right = D_X + 1.08f;
        float bottom = BOTTOM_Y - 0.70f;
        float top = BOTTOM_Y + 0.70f;
        quad(consumer, pose, left, bottom, left + 0.34f, top, 0.0f, 0.46f, 0.50f, 0.56f, 1.0f);
        quad(consumer, pose, left + 0.34f, top - 0.32f, right, top, 0.0f, 0.50f, 0.54f, 0.60f, 1.0f);
        quad(consumer, pose, left + 0.34f, bottom, right, bottom + 0.30f, 0.0f, 0.40f, 0.45f, 0.52f, 1.0f);
        quad(consumer, pose, D_X - 0.08f, BOTTOM_Y - 0.22f,
                D_X + 0.40f, BOTTOM_Y + 0.20f, 0.0f, 0.42f, 0.48f, 0.55f, 1.0f);
    }

    private static void writeCube(VertexConsumer consumer, Matrix4f pose, float cx, float cy, float cz,
                                  float hx, float hy, float hz, float red, float green, float blue) {
        float front = cz + hz;
        float back = cz - hz;
        quad3d(consumer, pose, cx - hx, cy - hy, front, cx + hx, cy - hy, front,
                cx + hx, cy + hy, front, cx - hx, cy + hy, front, red, green, blue, 1.0f);
        quad3d(consumer, pose, cx + hx, cy - hy, back, cx - hx, cy - hy, back,
                cx - hx, cy + hy, back, cx + hx, cy + hy, back, red * 0.62f, green * 0.62f, blue * 0.62f, 1.0f);
        quad3d(consumer, pose, cx - hx, cy - hy, back, cx - hx, cy - hy, front,
                cx - hx, cy + hy, front, cx - hx, cy + hy, back, red * 0.78f, green * 0.78f, blue * 0.78f, 1.0f);
        quad3d(consumer, pose, cx + hx, cy - hy, front, cx + hx, cy - hy, back,
                cx + hx, cy + hy, back, cx + hx, cy + hy, front, red * 0.86f, green * 0.86f, blue * 0.86f, 1.0f);
        quad3d(consumer, pose, cx - hx, cy + hy, front, cx + hx, cy + hy, front,
                cx + hx, cy + hy, back, cx - hx, cy + hy, back, red * 1.08f, green * 1.08f, blue * 1.08f, 1.0f);
        quad3d(consumer, pose, cx - hx, cy - hy, back, cx + hx, cy - hy, back,
                cx + hx, cy - hy, front, cx - hx, cy - hy, front, red * 0.48f, green * 0.48f, blue * 0.48f, 1.0f);
    }

    private static void quad(VertexConsumer consumer, Matrix4f pose, float left, float bottom,
                             float right, float top, float z, float red, float green, float blue, float alpha) {
        vertex(consumer, pose, left, bottom, z, red, green, blue, alpha);
        vertex(consumer, pose, right, bottom, z, red, green, blue, alpha);
        vertex(consumer, pose, right, top, z, red, green, blue, alpha);
        vertex(consumer, pose, left, top, z, red, green, blue, alpha);
    }

    private static void crossingQuad(VertexConsumer consumer, Matrix4f pose, float cx, float cy,
                                     float halfWidth, float halfHeight, float leftZ, float rightZ,
                                     float red, float green, float blue, float alpha) {
        vertex(consumer, pose, cx - halfWidth, cy - halfHeight, leftZ, red, green, blue, alpha);
        vertex(consumer, pose, cx + halfWidth, cy - halfHeight, rightZ, red, green, blue, alpha);
        vertex(consumer, pose, cx + halfWidth, cy + halfHeight, rightZ, red, green, blue, alpha);
        vertex(consumer, pose, cx - halfWidth, cy + halfHeight, leftZ, red, green, blue, alpha);
    }

    private static void orientedVertex(VertexConsumer consumer, Matrix4f pose, float cx, float cy,
                                       float ux, float uy, float vx, float vy, float u, float v,
                                       float z, float slope, float red, float green, float blue, float alpha) {
        vertex(consumer, pose, cx + ux * u + vx * v, cy + uy * u + vy * v,
                z + slope * u / 1.20f, red, green, blue, alpha);
    }

    private static void quad3d(VertexConsumer consumer, Matrix4f pose,
                               float x0, float y0, float z0, float x1, float y1, float z1,
                               float x2, float y2, float z2, float x3, float y3, float z3,
                               float red, float green, float blue, float alpha) {
        vertex(consumer, pose, x0, y0, z0, red, green, blue, alpha);
        vertex(consumer, pose, x1, y1, z1, red, green, blue, alpha);
        vertex(consumer, pose, x2, y2, z2, red, green, blue, alpha);
        vertex(consumer, pose, x3, y3, z3, red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y, float z,
                               float red, float green, float blue, float alpha) {
        consumer.addVertex(pose, x, y, z).setColor(red, green, blue, alpha);
    }

    static void renderHud(GuiGraphics graphics, OITVisualTestScene.RenderReport report,
                          OITVisualTestScene.SubmissionOrder order) {
        var minecraft = Minecraft.getInstance();
        int x = 8;
        int y = 8;
        int lineHeight = 10;
        int width = 360;
        int height = 151;
        graphics.fill(x - 4, y - 4, x + width, y + height, 0xD9151921);
        graphics.fill(x - 4, y - 4, x + width, y - 3, 0xFF70B7FF);
        drawLine(graphics, minecraft, "Kasuga OIT Visual Test", x, y, 0xFFFFFFFF);
        drawLine(graphics, minecraft, "Mode: " + OITVisualTestScene.renderMode().displayName
                + "   Actual: " + report.path(), x, y += lineHeight, pathColor(report), false);
        drawLine(graphics, minecraft, "F6 mode   F7 order   F8 buffer", x, y += lineHeight, 0xFFB5C4D8, false);
        drawLine(graphics, minecraft, "Submission order: " + order.displayName, x, y += lineHeight, 0xFFFFD166, false);
        drawLine(graphics, minecraft, "Framebuffer: " + report.framebuffer(), x, y += lineHeight, 0xFFC7CEDA, false);
        drawLine(graphics, minecraft, "Accum: " + report.accumulation() + "   Revealage: " + report.revealage(),
                x, y += lineHeight, 0xFFC7CEDA, false);
        drawLine(graphics, minecraft, "Depth: " + report.depth() + "   Depth write: OFF   Depth test: ON",
                x, y += lineHeight, 0xFFC7CEDA, false);
        drawLine(graphics, minecraft, "Resolution: " + report.width() + "x" + report.height()
                + "   Iris: " + safeIrisState(), x, y += lineHeight, 0xFFC7CEDA, false);
        drawLine(graphics, minecraft, "Buffer view: " + OITVisualTestScene.bufferView().displayName,
                x, y += lineHeight, 0xFF78B7FF, false);
        drawLine(graphics, minecraft, "Acceptance: C intersect  D depth  E permutation  F water",
                x, y += lineHeight, 0xFF9DE7B1, false);
        if (report.failure() != null) {
            drawLine(graphics, minecraft, "Failure/fallback: " + report.failure(),
                    x, y += lineHeight, 0xFFFF6868, false);
        }
        drawLine(graphics, minecraft, "A opaque   B mask ramp   C cross   D depth   E stress   F water",
                x, y += lineHeight, 0xFF9AA8BA, false);
    }

    private static void drawLine(GuiGraphics graphics, Minecraft minecraft, String text,
                                 int x, int y, int color) {
        drawLine(graphics, minecraft, text, x, y, color, true);
    }

    private static void drawLine(GuiGraphics graphics, Minecraft minecraft, String text,
                                 int x, int y, int color, boolean title) {
        graphics.drawString(minecraft.font, text, x, y, color, !title);
    }

    private static int pathColor(OITVisualTestScene.RenderReport report) {
        if (report.failure() != null) return 0xFFFF6868;
        return "WEIGHTED_OIT".equals(report.path()) ? 0xFF70E090 : 0xFFFFD166;
    }

    private static String safeIrisState() {
        try {
            return Boolean.toString(BackendInstance.isIrisEnabled());
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String conciseFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private static void releaseTarget() {
        if (target == null) return;
        try {
            target.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release OIT visual target", exception);
        } finally {
            target = null;
        }
    }

    private static void setUniform(ShaderInstance shader, String name, int value) {
        if (shader != null && shader.getUniform(name) != null) shader.getUniform(name).set(value);
    }

    private static void setUniform(ShaderInstance shader, String name, float value) {
        if (shader != null && shader.getUniform(name) != null) shader.getUniform(name).set(value);
    }

    @FunctionalInterface
    private interface GeometryWriter {
        void write(VertexConsumer consumer, Matrix4f pose);
    }
}
