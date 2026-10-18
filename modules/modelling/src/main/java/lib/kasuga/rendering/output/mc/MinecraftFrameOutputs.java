package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.output.FrameBlitter;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import lib.kasuga.rendering.output.gl.OutputFramebuffer;
import org.lwjgl.opengl.GL30;

import java.util.function.Consumer;

/** Final, post-GUI Minecraft output. No world target substitution or camera mutation. */
public final class MinecraftFrameOutputs {
    public static final String MAIN_VIEW = "minecraft:main";
    private static final FramebufferScope.Restorer RESTORER = (read, draw, viewport) -> {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    };

    private static final FrameOutputRouter<FrameTexture> ROUTER = new FrameOutputRouter<>(
            RenderSystem::assertOnRenderThread, () -> new OutputFramebuffer(RESTORER));

    private MinecraftFrameOutputs() {}

    static FramebufferScope.Restorer restorer() { return RESTORER; }

    public static boolean hasOutputs(String viewId) {
        RenderSystem.assertOnRenderThread();
        return ROUTER.hasOutputs(viewId);
    }

    public static FrameOutputRouter<FrameTexture>.Registration open(FrameOutputMode mode,
                                                                    Consumer<OutputFrame<FrameTexture>> consumer) {
        return open(MAIN_VIEW, mode, consumer);
    }

    /** Subscribe to a completed view, including a registered MinecraftWorldViews camera. */
    public static FrameOutputRouter<FrameTexture>.Registration open(String viewId, FrameOutputMode mode,
                                                                    Consumer<OutputFrame<FrameTexture>> consumer) {
        RenderSystem.assertOnRenderThread();
        return ROUTER.register(viewId, mode, consumer);
    }

    /** Entry for camera/custom producers after their view has reached final composition. */
    public static FrameOutputRouter.Delivery publish(String viewId, int width, int height, FrameBlitter blitter) {
        RenderSystem.assertOnRenderThread();
        FrameOutputRouter.Delivery delivery;
        try (var ignored = new FramebufferScope(RESTORER)) {
            delivery = ROUTER.publish(viewId, width, height, blitter);
        }
        for (Exception failure : delivery.failures()) {
            LogUtils.getLogger().error("Frame output failed and was detached for view {}", viewId, failure);
        }
        return delivery;
    }

    /** Mixin entry: preserve the ordinary presentation when no exclusive output succeeds. */
    public static void presentMain(int width, int height, FrameBlitter blitter) {
        if (!ROUTER.hasOutputs(MAIN_VIEW)) {
            blitter.draw(width, height);
            return;
        }
        if (!publish(MAIN_VIEW, width, height, blitter).suppressScreen()) blitter.draw(width, height);
    }

    /** Called before Minecraft tears down its render resources and window/context. */
    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        try { ROUTER.close(); }
        catch (Exception failure) { LogUtils.getLogger().error("Could not release all frame outputs", failure); }
    }
}
