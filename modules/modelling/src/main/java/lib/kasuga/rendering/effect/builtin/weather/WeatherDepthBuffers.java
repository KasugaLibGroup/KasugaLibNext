package lib.kasuga.rendering.effect.builtin.weather;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.mc.scene.CachedBlockGeometry;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Depth-only render buffer sink for capturing top-down rain occlusion into an offscreen FBO.
 * Translucent roofs (such as train skylight glass) are forced to write depth without writing color.
 */
public final class WeatherDepthBuffers implements CachedBlockGeometry.Sink, MultiBufferSource, AutoCloseable {
    private static final VertexConsumer NO_OP_CONSUMER = new NoOpVertexConsumer();

    private final ByteBufferBuilder shared = new ByteBufferBuilder(256);
    private final LinkedHashMap<RenderType, ByteBufferBuilder> buffers = new LinkedHashMap<>();
    private final MultiBufferSource.BufferSource source = MultiBufferSource.immediateWithBuffers(buffers, shared);
    private final Map<RenderType, RenderType> variants = new IdentityHashMap<>();
    private final LinkedHashMap<RenderType, List<CachedDraw>> cached = new LinkedHashMap<>();

    private int targetFramebuffer;
    private int targetSize;
    private boolean closed;

    private record CachedDraw(CachedBlockGeometry.Layer layer, Matrix4f modelView, Matrix4f projection) {}

    public void begin(int targetFramebuffer, int targetSize) {
        if (closed) {
            throw new IllegalStateException("Weather depth buffers are closed");
        }
        this.targetFramebuffer = targetFramebuffer;
        this.targetSize = targetSize;
    }

    @Override
    public void drawCached(CachedBlockGeometry.Layer layer, MinecraftModelSource.Context context) {
        if (isLineMode(layer.type().mode())) {
            return;
        }
        RenderType local = wrap(layer.type());
        List<CachedDraw> list = cached.computeIfAbsent(local, ignored -> new ArrayList<>());
        Matrix4f modelView = new Matrix4f(context.modelView()).mul(context.pose().last().pose());
        list.add(new CachedDraw(layer, modelView, new Matrix4f(context.projection())));
    }

    @Override
    public VertexConsumer getBuffer(RenderType original) {
        if (isLineMode(original.mode())) {
            return NO_OP_CONSUMER;
        }
        RenderType local = wrap(original);
        buffers.computeIfAbsent(local, type -> new ByteBufferBuilder(Math.min(65536, Math.max(256, type.bufferSize()))));
        return source.getBuffer(local);
    }

    private RenderType wrap(RenderType original) {
        return variants.computeIfAbsent(original, type -> new DepthLocalType(type, this::bindDepthTarget));
    }

    private static boolean isLineMode(VertexFormat.Mode mode) {
        return mode == VertexFormat.Mode.LINES
                || mode == VertexFormat.Mode.LINE_STRIP
                || mode == VertexFormat.Mode.DEBUG_LINES
                || mode == VertexFormat.Mode.DEBUG_LINE_STRIP;
    }

    private void bindDepthTarget() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFramebuffer);
        RenderSystem.viewport(0, 0, targetSize, targetSize);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
    }

    public void flush() {
        Set<RenderType> types = new LinkedHashSet<>(buffers.keySet());
        types.addAll(cached.keySet());
        try {
            for (RenderType type : types) {
                List<CachedDraw> draws = cached.get(type);
                if (draws != null && !draws.isEmpty()) {
                    type.setupRenderState();
                    try {
                        for (CachedDraw draw : draws) {
                            draw.layer.draw(draw.modelView, draw.projection);
                        }
                    } finally {
                        type.clearRenderState();
                    }
                }
                source.endBatch(type);
            }
        } finally {
            cached.clear();
            RenderSystem.colorMask(true, true, true, true);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            for (ByteBufferBuilder buffer : buffers.values()) {
                buffer.close();
            }
        } finally {
            shared.close();
            buffers.clear();
            variants.clear();
            cached.clear();
        }
    }

    private static final class DepthLocalType extends RenderType {
        private DepthLocalType(RenderType original, Runnable target) {
            super("kasuga_weather_depth/" + original,
                    original.format(),
                    original.mode(),
                    original.bufferSize(),
                    original.affectsCrumbling(),
                    false,
                    () -> {
                        original.setupRenderState();
                        target.run();
                    },
                    () -> {
                        RenderSystem.colorMask(true, true, true, true);
                        original.clearRenderState();
                    });
        }
    }

    private static final class NoOpVertexConsumer implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float normalX, float normalY, float normalZ) { return this; }
    }
}
