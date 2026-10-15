package lib.kasuga.create.client.rendering.edge_point;

import com.simibubi.create.content.trains.graph.EdgePointType;

import java.util.Map;
import java.util.WeakHashMap;

public final class EdgePointOverlayRendererRegistry {
    private final Map<EdgePointType<?>, EdgePointOverlayRenderer> renderers = new WeakHashMap<>();

    public EdgePointOverlayRenderer getRendererFor(EdgePointType<?> type) {
        synchronized (renderers) {
            return renderers.get(type);
        }
    }

    public void register(EdgePointType<?> type, EdgePointOverlayRenderer renderer) {
        synchronized (renderers) {
            renderers.put(type, renderer);
        }
    }
}
