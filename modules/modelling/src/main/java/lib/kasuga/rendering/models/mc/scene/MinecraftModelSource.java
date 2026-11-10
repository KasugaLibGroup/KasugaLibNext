package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

import java.util.List;
import java.util.Objects;

/**
 * Render-thread-owned model, backed by native Minecraft/mod renderers or UML geometry.
 * Snapshot once per draw; neither the preview nor an adapter may tick or modify a live world.
 * A preview owns the source passed to it. Borrowed game entities/templates remain caller-owned.
 */
public interface MinecraftModelSource extends AutoCloseable {
    Snapshot snapshot(LayerLayout layout, float partialTick);

    /** Keeps per-layer parts even when the model is fully assembled, for display animation. */
    default Snapshot snapshotLayers(LayerLayout layout, float partialTick) { return snapshot(layout, partialTick); }

    /** Nonempty source-local layers. Adapters may override without building a render snapshot. */
    default List<Integer> layerCoordinates(LayerLayout.Axis axis) {
        var frame = snapshot(LayerLayout.ASSEMBLED.axis(axis).separation(.000001), 0);
        return frame == null ? List.of() : frame.parts().stream().map(Part::layer).distinct().sorted().toList();
    }

    /** Counts source blocks, never rendered faces; models without block data return an empty map. */
    default java.util.Map<Integer, java.util.Map<net.minecraft.resources.ResourceLocation, Integer>> blockStatistics(LayerLayout.Axis axis) {
        return java.util.Map.of();
    }

    @Override default void close() {}

    @FunctionalInterface
    interface Renderer {
        void render(Context context);
    }

    /** Source block coordinates are stable; location is in the caller's display coordinate system. */
    record BlockHit(BlockPos position, BlockState state, Vec3 location, Direction face) {}
    @FunctionalInterface interface Picker { Optional<BlockHit> pick(Vec3 from, Vec3 to); }

    /** Applies exactly the same affine placement used to draw a source or exploded layer. */
    static Picker placedPicker(Matrix4fc placement, Picker picker) {
        var forward = new Matrix4f(placement); var inverse = new Matrix4f(forward).invert();
        if (!forward.isFinite() || !forward.isAffine() || !inverse.isFinite()) throw new IllegalArgumentException("Invalid pick placement");
        return (from, to) -> picker.pick(point(inverse, from), point(inverse, to))
                .map(hit -> new BlockHit(hit.position(), hit.state(), point(forward, hit.location()), hit.face()));
    }
    private static Vec3 point(Matrix4fc matrix, Vec3 point) {
        var result = matrix.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vec3(result.x, result.y, result.z);
    }

    /** Pose includes the view and source transform; projection is supplied explicitly for UML draws. */
    record Context(PoseStack pose, MultiBufferSource buffers, Matrix4f projection, float partialTick, int packedLight, Matrix4f modelView) {
        public Context(PoseStack pose, MultiBufferSource buffers, Matrix4f projection, float partialTick, int packedLight) {
            this(pose, buffers, projection, partialTick, packedLight, new Matrix4f());
        }
    }

    /** Renderer and bounds describe one frame. Borrowed callbacks are valid until the next snapshot or close. */
    record Part(String id, int layer, ModelBounds bounds, Renderer renderer, Picker picker) {
        public Part(String id, int layer, ModelBounds bounds, Renderer renderer) {
            this(id, layer, bounds, renderer, (from, to) -> Optional.empty());
        }
        public Part {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(renderer, "renderer");
            Objects.requireNonNull(picker, "picker");
        }
    }

    /** Empty parts mean no visible layers; bounds still describe the full exploded model for stable framing. */
    record Snapshot(ModelBounds bounds, List<Part> parts) {
        public Snapshot {
            Objects.requireNonNull(bounds, "bounds");
            parts = List.copyOf(parts);
        }
        public void render(Context context) {
            for (Part part : parts) part.renderer().render(context);
        }
        public Optional<BlockHit> pick(Vec3 from, Vec3 to) {
            return parts.stream().map(part -> part.picker().pick(from, to)).flatMap(Optional::stream)
                    .min(java.util.Comparator.comparingDouble(hit -> hit.location().distanceToSqr(from)));
        }
    }
}
