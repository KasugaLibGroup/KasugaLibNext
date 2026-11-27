package lib.kasuga.geometry;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An immutable regularized axis-aligned solid.
 *
 * <p>The boxes are the actual result, are non-overlapping, and preserve holes and disconnected
 * components. The total bounds are available separately for broad-phase use.</p>
 */
public final class Shape {
    private static final Shape EMPTY = new Shape(List.of(), Bounds.empty(), List.of(), 0);

    private final List<Box> boxes;
    private final Bounds bounds;
    private final List<BoundaryEdge> boundaryEdges;
    private final int depth;

    private Shape(List<Box> boxes, Bounds bounds, List<BoundaryEdge> boundaryEdges, int depth) {
        this.boxes = boxes;
        this.bounds = bounds;
        this.boundaryEdges = boundaryEdges;
        this.depth = depth;
    }

    public static Shape empty() {
        return EMPTY;
    }

    public static Shape of(Box... boxes) {
        return of(List.of(boxes), GeometryBudget.standard());
    }

    public static Shape of(Collection<Box> boxes) {
        return of(boxes, GeometryBudget.standard());
    }

    public static Shape of(Collection<Box> boxes, GeometryBudget budget) {
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(budget, "budget");
        return Csg.normalize(boxes, 0, budget);
    }

    public Shape union(Shape... others) {
        return union(GeometryBudget.standard(), others);
    }

    public Shape union(GeometryBudget budget, Shape... others) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(others, "others");
        if (others.length == 0) {
            return this;
        }
        if (others.length == Integer.MAX_VALUE) {
            throw new GeometryException("too many union operands");
        }
        Shape[] operands = new Shape[others.length + 1];
        operands[0] = this;
        System.arraycopy(others, 0, operands, 1, others.length);
        return union(List.of(operands), budget);
    }

    public static Shape union(Collection<? extends Shape> shapes) {
        return union(shapes, GeometryBudget.standard());
    }

    public static Shape union(Collection<? extends Shape> shapes, GeometryBudget budget) {
        Objects.requireNonNull(shapes, "shapes");
        Objects.requireNonNull(budget, "budget");
        List<Shape> operands = List.copyOf(shapes);
        if (operands.isEmpty()) {
            return EMPTY;
        }
        int depth = nextDepth(operands, budget);
        return Csg.union(operands, depth, budget);
    }

    public Shape intersection(Shape... others) {
        return intersection(GeometryBudget.standard(), others);
    }

    public Shape intersection(GeometryBudget budget, Shape... others) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(others, "others");
        if (others.length == 0) {
            return this;
        }
        if (others.length == Integer.MAX_VALUE) {
            throw new GeometryException("too many intersection operands");
        }
        Shape[] operands = new Shape[others.length + 1];
        operands[0] = this;
        System.arraycopy(others, 0, operands, 1, others.length);
        return intersection(List.of(operands), budget);
    }

    public static Shape intersection(Collection<? extends Shape> shapes) {
        return intersection(shapes, GeometryBudget.standard());
    }

    public static Shape intersection(Collection<? extends Shape> shapes, GeometryBudget budget) {
        Objects.requireNonNull(shapes, "shapes");
        Objects.requireNonNull(budget, "budget");
        List<Shape> operands = List.copyOf(shapes);
        if (operands.isEmpty()) {
            throw new GeometryException("intersection requires at least one operand");
        }
        int depth = nextDepth(operands, budget);
        return Csg.intersection(operands, depth, budget);
    }

    /**
     * Applies {@code ((this - subtrahends[0]) - subtrahends[1]) ...}.
     */
    public Shape difference(Shape... subtrahends) {
        return difference(GeometryBudget.standard(), subtrahends);
    }

    public Shape difference(GeometryBudget budget, Shape... subtrahends) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(subtrahends, "subtrahends");
        if (subtrahends.length == 0) {
            return this;
        }
        int depth = nextDepth(this, subtrahends, budget);
        return Csg.difference(this, List.of(subtrahends), depth, budget);
    }

    public Shape transform(Transform transform) {
        return transform(transform, GeometryBudget.standard());
    }

    public Shape transform(Transform transform, GeometryBudget budget) {
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(budget, "budget");
        budget.checkDepth(depth);
        if (isEmpty()) {
            return this;
        }
        List<Box> transformed = new java.util.ArrayList<>(boxes.size());
        for (Box box : boxes) {
            transformed.add(transform.apply(box));
        }
        return Csg.normalize(transformed, depth, budget);
    }

    public List<Box> boxes() {
        return boxes;
    }

    /** Total extent only; this does not describe holes or disconnected regions. */
    public Bounds bounds() {
        return bounds;
    }

    /** Final exterior and hole edges, with coplanar internal seams merged away. */
    public List<BoundaryEdge> boundaryEdges() {
        return boundaryEdges;
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    public int depth() {
        return depth;
    }

    public double volume() {
        double volume = 0.0;
        for (Box box : boxes) {
            volume += box.volume();
        }
        return volume;
    }

    /** Closed-boundary membership. A point on an exterior or hole face is contained. */
    public boolean contains(float x, float y, float z) {
        for (Box box : boxes) {
            if (box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(Point point) {
        Objects.requireNonNull(point, "point");
        return contains(point.x(), point.y(), point.z());
    }

    static Shape ofNormalized(List<Box> boxes, Bounds bounds, List<BoundaryEdge> edges, int depth) {
        if (boxes.isEmpty()) {
            return depth == 0 ? EMPTY : new Shape(List.of(), Bounds.empty(), List.of(), depth);
        }
        return new Shape(List.copyOf(boxes), bounds, List.copyOf(edges), depth);
    }

    static Shape emptyAtDepth(int depth) {
        return depth == 0 ? EMPTY : new Shape(List.of(), Bounds.empty(), List.of(), depth);
    }

    private static int nextDepth(List<? extends Shape> shapes, GeometryBudget budget) {
        int maximum = 0;
        for (Shape shape : shapes) {
            Objects.requireNonNull(shape, "shape");
            maximum = Math.max(maximum, shape.depth);
        }
        if (maximum == Integer.MAX_VALUE) {
            throw new GeometryException("shape operation depth overflowed");
        }
        int depth = maximum + 1;
        budget.checkDepth(depth);
        return depth;
    }

    private static int nextDepth(Shape first, Shape[] rest, GeometryBudget budget) {
        int maximum = Objects.requireNonNull(first, "first").depth;
        for (Shape shape : rest) {
            maximum = Math.max(maximum, Objects.requireNonNull(shape, "subtrahend").depth);
        }
        if (maximum == Integer.MAX_VALUE) {
            throw new GeometryException("shape operation depth overflowed");
        }
        int depth = maximum + 1;
        budget.checkDepth(depth);
        return depth;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Shape shape)) return false;
        return boxes.equals(shape.boxes);
    }

    @Override
    public int hashCode() {
        return boxes.hashCode();
    }

    @Override
    public String toString() {
        return "Shape[boxes=" + boxes + ", depth=" + depth + ']';
    }
}
