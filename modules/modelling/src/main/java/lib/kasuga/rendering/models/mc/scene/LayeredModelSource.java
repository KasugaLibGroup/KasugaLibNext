package lib.kasuga.rendering.models.mc.scene;

import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reusable layer planning for native renderers, including third-party schematic adapters. */
public abstract class LayeredModelSource implements MinecraftModelSource {
    /** Positions and bounds use one source-local coordinate system. */
    public record Element(int x, int y, int z, ModelBounds bounds) {}

    public record Slice(LayerLayout.Axis axis, int coordinate, List<Element> elements, boolean isolated) {
        public Slice { elements = List.copyOf(elements); }
    }

    protected abstract List<Element> elements();
    protected abstract void renderSlice(Slice slice, Context context);
    protected java.util.Optional<BlockHit> pickSlice(Slice slice, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        return java.util.Optional.empty();
    }
    /** Override to call an upstream whole-model renderer when all layers are assembled. */
    protected void renderAssembled(Context context) {
        renderSlice(new Slice(LayerLayout.Axis.Y, 0, elements(), false), context);
    }
    /** Dynamic sources refresh only their private render data here, once per snapshot. */
    protected void prepare(float partialTick) {}
    protected Matrix4f localTransform(float partialTick) { return new Matrix4f(); }
    protected boolean closed;

    @Override @Nullable public final Snapshot snapshot(LayerLayout layout, float partialTick) {
        if (closed) return null;
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("partialTick must be finite");
        prepare(partialTick);
        List<Element> elements = List.copyOf(elements());
        if (elements.isEmpty()) return null;
        if (elements.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Too many model elements");
        Matrix4f transform = localTransform(partialTick);
        Map<Integer, List<Element>> layers = new TreeMap<>();
        ModelBounds assembledBounds = null;
        for (Element element : elements) {
            int layer = layout.axis().coordinate(element.x(), element.y(), element.z());
            layers.computeIfAbsent(layer, key -> new ArrayList<>()).add(element);
            assembledBounds = assembledBounds == null ? element.bounds() : assembledBounds.union(element.bounds());
        }
        int first = layers.keySet().iterator().next();
        if (layers.size() > MinecraftModelSources.MAX_LAYERS) throw new IllegalArgumentException("Too many model layers");
        if (layout.separation() == 0 && layers.keySet().stream().allMatch(layout::includes)) {
            var slice = new Slice(layout.axis(), first, elements, false);
            return new Snapshot(assembledBounds.transform(transform), List.of(new Part("assembled", first,
                    assembledBounds.transform(transform), ctx -> transformed(ctx, transform, 0, layout.axis(), this::renderAssembled),
                    MinecraftModelSource.placedPicker(transform, (from, to) -> pickSlice(slice, from, to)))));
        }
        List<Part> parts = new ArrayList<>();
        ModelBounds bounds = null;
        for (var entry : layers.entrySet()) {
            int layer = entry.getKey();
            ModelBounds layerBounds = entry.getValue().stream().map(Element::bounds).reduce(ModelBounds::union).orElseThrow();
            double offset = layout.offset(layer, first);
            ModelBounds displayed = layerBounds.move(layout.axis(), offset).transform(transform);
            bounds = bounds == null ? displayed : bounds.union(displayed);
            if (layout.includes(layer)) {
                var slice = new Slice(layout.axis(), layer, entry.getValue(), true);
                parts.add(new Part("layer/" + layer, layer, displayed,
                        ctx -> transformed(ctx, transform, offset, layout.axis(), c -> renderSlice(slice, c)),
                        MinecraftModelSource.placedPicker(new Matrix4f(transform).translate(
                                layout.axis() == LayerLayout.Axis.X ? (float) offset : 0,
                                layout.axis() == LayerLayout.Axis.Y ? (float) offset : 0,
                                layout.axis() == LayerLayout.Axis.Z ? (float) offset : 0), (from, to) -> pickSlice(slice, from, to))));
            }
        }
        return new Snapshot(bounds, parts);
    }

    private static void transformed(Context context, Matrix4f transform, double offset, LayerLayout.Axis axis, Renderer draw) {
        context.pose().pushPose();
        try {
            context.pose().mulPose(transform);
            context.pose().translate(axis == LayerLayout.Axis.X ? offset : 0,
                    axis == LayerLayout.Axis.Y ? offset : 0, axis == LayerLayout.Axis.Z ? offset : 0);
            draw.render(context);
        } finally { context.pose().popPose(); }
    }

    @Override public void close() { closed = true; }
}
