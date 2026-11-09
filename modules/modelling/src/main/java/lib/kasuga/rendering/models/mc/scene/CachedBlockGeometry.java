package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Source-local static native geometry. Texture animation and all view/entity transforms remain live. */
public final class CachedBlockGeometry implements AutoCloseable {
    public interface Sink extends MultiBufferSource {
        void drawCached(Layer layer, MinecraftModelSource.Context context);
    }
    private final List<Layer> layers;
    private CachedBlockGeometry(List<Layer> layers) { this.layers = List.copyOf(layers); }
    public static CachedBlockGeometry build(MinecraftModelSource.Renderer draw) {
        var collectors = new LinkedHashMap<RenderType, Collector>();
        var layers = new ArrayList<Layer>();
        try {
            draw.render(new MinecraftModelSource.Context(new PoseStack(), type -> collectors.computeIfAbsent(type, Collector::new).vertices,
                    new Matrix4f(), 0, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT));
            for (var entry : collectors.entrySet()) {
                var mesh = entry.getValue().vertices.build();
                if (mesh != null) layers.add(new Layer(entry.getKey(), mesh));
            }
            return new CachedBlockGeometry(layers);
        } catch (RuntimeException | Error failure) { layers.forEach(Layer::close); throw failure; }
        finally { collectors.values().forEach(collector -> collector.storage.close()); }
    }
    public void render(MinecraftModelSource.Context context) {
        var sink = (Sink) context.buffers();
        for (var layer : layers) {
            if (layer.translucent != null) layer.replayTranslucent(context);
            else sink.drawCached(layer, context);
        }
    }
    @Override public void close() { layers.forEach(Layer::close); }
    private static final class Collector {
        final ByteBufferBuilder storage;
        final BufferBuilder vertices;
        Collector(RenderType type) {
            storage = new ByteBufferBuilder(Math.min(65536,Math.max(256,type.bufferSize())));
            vertices = new BufferBuilder(storage,type.mode(),type.format());
        }
    }
    public static final class Layer implements AutoCloseable {
        private final RenderType type;
        private VertexBuffer gpu;
        private final ByteBufferBuilder indices;
        private final MeshData.SortState sorting;
        private Matrix4f sortedView;
        private java.nio.ByteBuffer translucent;
        private int vertexCount;
        private boolean closed;
        private Layer(RenderType type, MeshData mesh) {
            this.type = type;
            indices = type.sortOnUpload() && !type.format().equals(DefaultVertexFormat.BLOCK) ? new ByteBufferBuilder(256) : null;
            boolean transferred = false;
            try {
                if (type.sortOnUpload() && type.format().equals(DefaultVertexFormat.BLOCK)) {
                    vertexCount = mesh.drawState().vertexCount();
                    var bytes = new byte[mesh.vertexBuffer().remaining()]; mesh.vertexBuffer().get(bytes);
                    translucent = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder());
                    sorting = null; transferred=true; mesh.close(); return;
                }
                sorting = indices == null ? null : mesh.sortQuads(indices,VertexSorting.DISTANCE_TO_ORIGIN);
                gpu=new VertexBuffer(VertexBuffer.Usage.STATIC);
                gpu.bind(); transferred=true; gpu.upload(mesh);
            } catch (RuntimeException | Error error) {
                if (!transferred) mesh.close(); if (gpu!=null) gpu.close(); if (indices != null) indices.close(); throw error;
            } finally { VertexBuffer.unbind(); }
        }
        public RenderType type() { return type; }
        private void replayTranslucent(MinecraftModelSource.Context context) {
            // Keep all transparent quads in the caller's shared sort, including other layers and
            // animated block entities. Only opaque geometry bypasses per-frame vertex submission.
            var target = context.buffers().getBuffer(type); var pose = context.pose().last();
            var format = type.format(); int stride = format.getVertexSize();
            int position = format.getOffset(VertexFormatElement.POSITION), color = format.getOffset(VertexFormatElement.COLOR);
            int uv = format.getOffset(VertexFormatElement.UV0), light = format.getOffset(VertexFormatElement.UV2);
            int normal = format.getOffset(VertexFormatElement.NORMAL);
            for (int i=0;i<vertexCount;i++) {
                int base=i*stride;
                target.addVertex(pose,translucent.getFloat(base+position),translucent.getFloat(base+position+4),translucent.getFloat(base+position+8))
                        .setColor(translucent.get(base+color)&255,translucent.get(base+color+1)&255,translucent.get(base+color+2)&255,translucent.get(base+color+3)&255)
                        .setUv(translucent.getFloat(base+uv),translucent.getFloat(base+uv+4))
                        .setUv2(translucent.getShort(base+light)&65535,translucent.getShort(base+light+2)&65535)
                        .setNormal(pose,translucent.get(base+normal)/127f,translucent.get(base+normal+1)/127f,translucent.get(base+normal+2)/127f);
            }
        }
        public void draw(Matrix4f modelView, Matrix4f projection) {
            if (closed) throw new IllegalStateException("Cached native geometry is closed");
            gpu.bind();
            try {
                if (sorting != null && (sortedView == null || !sortedView.equals(modelView))) {
                    var position = new Vector3f();
                    gpu.uploadIndexBuffer(sorting.buildSortedIndexBuffer(indices,VertexSorting.byDistance(
                            point -> modelView.transformPosition(point,position).lengthSquared())));
                    sortedView = new Matrix4f(modelView);
                }
                gpu.drawWithShader(modelView,projection,java.util.Objects.requireNonNull(RenderSystem.getShader()));
            } finally { VertexBuffer.unbind(); }
        }
        @Override public void close() {
            if (closed) return; closed=true;
            translucent = null;
            try { if (gpu!=null) gpu.close(); } finally { if (indices != null) indices.close(); }
        }
    }
}
