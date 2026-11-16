package lib.kasuga.rendering.models.uml.backend;

/**
 * Raster depth offset for an explicitly layered model, without moving its geometry.
 * The slope factor scales the polygon's depth slope; units scale the backend's
 * minimum resolvable depth offset. Negative values pull forward with conventional depth.
 */
public record DepthBias(float factor, float units) {
    public static final DepthBias NONE = new DepthBias(0, 0);
    /** A small forward offset for a decal or another intentionally coplanar overlay. */
    public static final DepthBias DECAL = new DepthBias(-1, -1);

    public DepthBias {
        if (!Float.isFinite(factor) || !Float.isFinite(units)) {
            throw new IllegalArgumentException("Depth bias must be finite");
        }
        // Equal zero policies must share a batch, including callers supplying -0.0f.
        if (factor == 0) factor = 0;
        if (units == 0) units = 0;
    }

    public boolean enabled() {
        return factor != 0 || units != 0;
    }
}
