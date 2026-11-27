package lib.kasuga.rendering.models.mc.shape;

import lib.kasuga.geometry.BoundaryEdge;
import lib.kasuga.geometry.Shape;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-coordinate outline view of the same final geometry used for block queries.
 *
 * <p>The geometry remains float in block-local coordinates. World positions are added only in
 * double precision, so distant anchors are not rounded before a renderer converts relative
 * coordinates for submission.</p>
 */
public final class ShapeOutlineGeometry {
    private ShapeOutlineGeometry() {
    }

    public static List<Segment> local(Shape shape) {
        Objects.requireNonNull(shape, "shape");
        List<Segment> result = new ArrayList<>(shape.boundaryEdges().size());
        for (BoundaryEdge edge : shape.boundaryEdges()) {
            result.add(new Segment(
                    edge.startX(), edge.startY(), edge.startZ(),
                    edge.endX(), edge.endY(), edge.endZ()
            ));
        }
        return List.copyOf(result);
    }

    public static List<Segment> at(Shape shape, BlockPos origin) {
        Objects.requireNonNull(origin, "origin");
        List<Segment> local = local(shape);
        List<Segment> result = new ArrayList<>(local.size());
        for (Segment segment : local) {
            result.add(new Segment(
                    origin.getX() + segment.startX(),
                    origin.getY() + segment.startY(),
                    origin.getZ() + segment.startZ(),
                    origin.getX() + segment.endX(),
                    origin.getY() + segment.endY(),
                    origin.getZ() + segment.endZ()
            ));
        }
        return List.copyOf(result);
    }

    public record Segment(
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ
    ) {
    }
}
