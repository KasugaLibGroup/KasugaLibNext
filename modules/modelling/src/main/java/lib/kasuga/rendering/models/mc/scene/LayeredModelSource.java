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
    /** Opt-in immutable geometry identity. Replace the key when elements change; null keeps live rebuilding. */
    protected Object geometryKey() { return null; }
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
    private Object plannedGeometry;
    private final java.util.EnumMap<LayerLayout.Axis, Plan> plans = new java.util.EnumMap<>(LayerLayout.Axis.class);
    private record Layer(int coordinate, Slice slice, ModelBounds bounds) {}
    private record Plan(List<Element> elements, Slice assembled, ModelBounds bounds, List<Layer> layers, int first) {}

    @Override public final List<Integer> layerCoordinates(LayerLayout.Axis axis) {
        if (closed) return List.of();
        var plan = plan(axis);
        return plan == null ? List.of() : plan.layers.stream().map(Layer::coordinate).toList();
    }

    private Plan plan(LayerLayout.Axis axis) {
        Object key = geometryKey();
        if (key != plannedGeometry) { plans.clear(); plannedGeometry = key; }
        Plan cached = key == null ? null : plans.get(axis);
        if (cached != null) return cached;
        List<Element> elements = List.copyOf(elements());
        if (elements.isEmpty()) return null;
        if (elements.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Too many model elements");
        var grouped = new TreeMap<Integer,List<Element>>();
        ModelBounds bounds = null;
        for (Element element : elements) {
            grouped.computeIfAbsent(axis.coordinate(element.x(),element.y(),element.z()), ignored -> new ArrayList<>()).add(element);
            bounds = bounds == null ? element.bounds() : bounds.union(element.bounds());
        }
        if (grouped.size() > MinecraftModelSources.MAX_LAYERS) throw new IllegalArgumentException("Too many model layers");
        var layers = new ArrayList<Layer>(grouped.size());
        for (var entry : grouped.entrySet()) layers.add(new Layer(entry.getKey(), new Slice(axis, entry.getKey(), entry.getValue(),true),
                entry.getValue().stream().map(Element::bounds).reduce(ModelBounds::union).orElseThrow()));
        int first = grouped.firstKey();
        var plan = new Plan(elements,new Slice(axis,first,elements,false),bounds,List.copyOf(layers),first);
        if (key != null) plans.put(axis,plan);
        return plan;
    }

    @Override @Nullable public final Snapshot snapshot(LayerLayout layout, float partialTick) {
        return snapshot(layout, partialTick, false);
    }

    @Override @Nullable public final Snapshot snapshotLayers(LayerLayout layout, float partialTick) {
        return snapshot(layout, partialTick, true);
    }

    @Nullable private Snapshot snapshot(LayerLayout layout, float partialTick, boolean forceLayers) {
        if (closed) return null;
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("partialTick must be finite");
        prepare(partialTick);
        Plan plan = plan(layout.axis());
        if (plan == null) return null;
        Matrix4f transform = localTransform(partialTick);
        if (!forceLayers && layout.separation() == 0 && plan.layers.stream().allMatch(layer -> layout.includes(layer.coordinate))) {
            var bounds = plan.bounds.transform(transform);
            return new Snapshot(bounds, List.of(new Part("assembled", plan.first, bounds,
                    ctx -> transformed(ctx, transform, 0, layout.axis(), this::renderAssembled),
                    MinecraftModelSource.placedPicker(transform, (from, to) -> pickSlice(plan.assembled, from, to)))));
        }
        List<Part> parts = new ArrayList<>();
        ModelBounds bounds = null;
        for (var entry : plan.layers) {
            int layer = entry.coordinate;
            ModelBounds layerBounds = entry.bounds;
            double offset = layout.offset(layer, plan.first);
            ModelBounds displayed = layerBounds.move(layout.axis(), offset).transform(transform);
            bounds = bounds == null ? displayed : bounds.union(displayed);
            if (layout.includes(layer)) {
                var slice = entry.slice;
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

    @Override public void close() { closed = true; plans.clear(); plannedGeometry = null; }
}
