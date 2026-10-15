package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Render-thread-owned reusable texture targets for post-processing passes. */
public final class PostProcessTargetPool {
    private static final PostProcessTargetPool INSTANCE = new PostProcessTargetPool();

    private final Map<ResourceLocation, Entry> targets = new HashMap<>();

    private PostProcessTargetPool() {}

    public static PostProcessTargetPool getInstance() {
        return INSTANCE;
    }

    public RenderTarget acquire(PostProcessTargetDescriptor descriptor, RenderTarget reference) {
        RenderSystem.assertOnRenderThread();
        int width = descriptor.resolveWidth(reference.viewWidth);
        int height = descriptor.resolveHeight(reference.viewHeight);
        Entry existing = targets.get(descriptor.id());
        if (existing != null && existing.descriptor().equals(descriptor)
                && existing.target().viewWidth == width && existing.target().viewHeight == height) {
            return existing.target();
        }
        if (existing != null) existing.target().destroyBuffers();

        TextureTarget target = new TextureTarget(width, height, descriptor.useDepth(), Minecraft.ON_OSX);
        target.setFilterMode(descriptor.filter().glConstant());
        target.setClearColor(
                descriptor.clearRed(), descriptor.clearGreen(),
                descriptor.clearBlue(), descriptor.clearAlpha()
        );
        targets.put(descriptor.id(), new Entry(descriptor, target));
        return target;
    }

    public Optional<RenderTarget> get(ResourceLocation id) {
        Entry entry = targets.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.target());
    }

    public void release(ResourceLocation id) {
        RenderSystem.assertOnRenderThread();
        Entry entry = targets.remove(id);
        if (entry != null) entry.target().destroyBuffers();
    }

    public void clear() {
        RenderSystem.assertOnRenderThread();
        targets.values().forEach(entry -> entry.target().destroyBuffers());
        targets.clear();
    }

    public int size() {
        return targets.size();
    }

    public void copyColor(RenderTarget source, RenderTarget destination, boolean linearFilter) {
        RenderSystem.assertOnRenderThread();
        if (source == destination) {
            throw new IllegalArgumentException("A render target cannot be copied into itself");
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GlStateManager._glBlitFrameBuffer(
                0, 0, source.viewWidth, source.viewHeight,
                0, 0, destination.viewWidth, destination.viewHeight,
                GL11.GL_COLOR_BUFFER_BIT,
                linearFilter ? GL11.GL_LINEAR : GL11.GL_NEAREST
        );
        source.bindWrite(true);
    }

    public void copyDepth(RenderTarget source, RenderTarget destination) {
        RenderSystem.assertOnRenderThread();
        if (!source.useDepth || !destination.useDepth) {
            throw new IllegalArgumentException("Both render targets must have depth attachments");
        }
        destination.copyDepthFrom(source);
        source.bindWrite(true);
    }

    private record Entry(PostProcessTargetDescriptor descriptor, TextureTarget target) {}
}
