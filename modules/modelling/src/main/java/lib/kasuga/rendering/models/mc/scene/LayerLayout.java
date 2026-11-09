package lib.kasuga.rendering.models.mc.scene;

/** Immutable display layout. Layer coordinates always belong to the source's local frame. */
public record LayerLayout(Axis axis, int firstLayer, int lastLayer, double separation) {
    public static final LayerLayout ASSEMBLED = new LayerLayout(Axis.Y, Integer.MIN_VALUE, Integer.MAX_VALUE, 0);

    public enum Axis {
        X, Y, Z;

        public int coordinate(int x, int y, int z) {
            return switch (this) { case X -> x; case Y -> y; case Z -> z; };
        }
    }

    public LayerLayout {
        java.util.Objects.requireNonNull(axis, "axis");
        if (firstLayer > lastLayer) throw new IllegalArgumentException("Layer range must be ordered");
        if (!Double.isFinite(separation) || separation < 0)
            throw new IllegalArgumentException("Layer separation must be finite and nonnegative");
    }

    public LayerLayout range(int first, int last) { return new LayerLayout(axis, first, last, separation); }
    public LayerLayout axis(Axis value) { return new LayerLayout(value, firstLayer, lastLayer, separation); }
    public LayerLayout separation(double value) { return new LayerLayout(axis, firstLayer, lastLayer, value); }
    public boolean includes(int layer) { return layer >= firstLayer && layer <= lastLayer; }

    /** Extra displacement in blocks; source geometry itself is never modified. */
    public double offset(int layer, int sourceFirstLayer) {
        double value = ((double) layer - sourceFirstLayer) * separation;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Layer displacement exceeds finite range");
        return value;
    }
}
