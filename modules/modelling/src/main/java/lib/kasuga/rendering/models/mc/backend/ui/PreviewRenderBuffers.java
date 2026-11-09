package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import lib.kasuga.rendering.models.mc.scene.CachedBlockGeometry;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owned buffers and local output variants: native render types cannot redirect a UI draw to world targets. */
public final class PreviewRenderBuffers implements CachedBlockGeometry.Sink, AutoCloseable {
    private final ByteBufferBuilder shared = new ByteBufferBuilder(256);
    private final LinkedHashMap<RenderType, ByteBufferBuilder> buffers = new LinkedHashMap<>();
    private final MultiBufferSource.BufferSource source = MultiBufferSource.immediateWithBuffers(buffers, shared);
    private final Map<RenderType, RenderType> variants = new IdentityHashMap<>();
    private int target, width, height;
    private boolean closed;
    private final LinkedHashMap<RenderType, java.util.List<CachedDraw>> cached = new LinkedHashMap<>();
    private record CachedDraw(CachedBlockGeometry.Layer layer, Matrix4f modelView, Matrix4f projection) {}

    @Override public void drawCached(CachedBlockGeometry.Layer layer, MinecraftModelSource.Context context) {
        RenderType local = variants.computeIfAbsent(layer.type(), type -> new LocalType(type, this::bindTarget));
        cached.computeIfAbsent(local, ignored -> new java.util.ArrayList<>()).add(new CachedDraw(layer,
                new Matrix4f(context.modelView()).mul(context.pose().last().pose()),new Matrix4f(context.projection())));
    }

    public void begin(int target, int width, int height) {
        if (closed) throw new IllegalStateException("Preview buffers are closed");
        this.target = target; this.width = width; this.height = height;
    }
    @Override public VertexConsumer getBuffer(RenderType original) {
        RenderType local = variants.computeIfAbsent(original, type -> new LocalType(type, this::bindTarget));
        buffers.computeIfAbsent(local, type -> new ByteBufferBuilder(Math.min(65536, Math.max(256, type.bufferSize()))));
        return source.getBuffer(local);
    }
    private void bindTarget() {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
        RenderSystem.viewport(0, 0, width, height);
        if (GL11.glIsEnabled(GL11.GL_BLEND)) {
            RenderSystem.blendFuncSeparate(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
    }
    public void flush() {
        // A glass block encountered first must not put its depth in front of opaque geometry.
        var types = new java.util.LinkedHashSet<>(buffers.keySet()); types.addAll(cached.keySet());
        try {
            for (var type : types.stream().sorted(java.util.Comparator.comparing(RenderType::sortOnUpload)).toList()) {
                var draws = cached.get(type);
                if (draws != null) {
                    if (type.sortOnUpload()) draws.sort(java.util.Comparator.comparingDouble((CachedDraw draw) ->
                            draw.modelView.m30()*draw.modelView.m30()+draw.modelView.m31()*draw.modelView.m31()+draw.modelView.m32()*draw.modelView.m32()).reversed());
                    type.setupRenderState();
                    try { for (var draw : draws) draw.layer.draw(draw.modelView,draw.projection); }
                    finally { type.clearRenderState(); }
                }
                source.endBatch(type);
            }
        } finally { cached.clear(); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            for (var buffer : buffers.values()) buffer.close();
        } finally {
            shared.close(); buffers.clear(); variants.clear(); cached.clear();
        }
    }
    private static final class LocalType extends RenderType {
        private LocalType(RenderType original, Runnable target) {
            super("kasuga_preview/" + original, original.format(), original.mode(), original.bufferSize(),
                    original.affectsCrumbling(), original.sortOnUpload(),
                    () -> { original.setupRenderState(); target.run(); }, original::clearRenderState);
        }
    }
}
