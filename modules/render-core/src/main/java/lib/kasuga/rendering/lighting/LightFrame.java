package lib.kasuga.rendering.lighting;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.Objects;

/** Immutable placement of a local light. Rectangle axes retain parent rotation, roll and scale. */
public record LightFrame(LightSource source, Matrix4d transform) {
    public LightFrame(LightSource source, Matrix4dc transform) { this(source, new Matrix4d(transform)); }

    public LightFrame {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(transform, "transform");
        if (!transform.isFinite() || !transform.isAffine() || transform.determinant() == 0) {
            throw new IllegalArgumentException("Light transform must be finite, affine and invertible");
        }
        transform = new Matrix4d(transform);
    }

    public static LightFrame world(LightSource source) { return new LightFrame(source, new Matrix4d()); }
    @Override public Matrix4d transform() { return new Matrix4d(transform); }
    public Vector3d position() { return transform.transformPosition(new Vector3d(source.x(), source.y(), source.z())); }

    /** Enclosing sphere for range/volume under nonuniform scale or shear. */
    public double rangeScale() {
        double one = Math.max(Math.abs(transform.m00()) + Math.abs(transform.m01()) + Math.abs(transform.m02()),
                Math.max(Math.abs(transform.m10()) + Math.abs(transform.m11()) + Math.abs(transform.m12()),
                        Math.abs(transform.m20()) + Math.abs(transform.m21()) + Math.abs(transform.m22())));
        double infinity = Math.max(Math.abs(transform.m00()) + Math.abs(transform.m10()) + Math.abs(transform.m20()),
                Math.max(Math.abs(transform.m01()) + Math.abs(transform.m11()) + Math.abs(transform.m21()),
                        Math.abs(transform.m02()) + Math.abs(transform.m12()) + Math.abs(transform.m22())));
        // For orthogonal columns this is the largest axis scale; avoid enlarging rotated rigid lights.
        Vector3d x = transform.transformDirection(new Vector3d(1, 0, 0));
        Vector3d y = transform.transformDirection(new Vector3d(0, 1, 0));
        Vector3d z = transform.transformDirection(new Vector3d(0, 0, 1));
        double maximum = Math.max(x.length(), Math.max(y.length(), z.length()));
        double tolerance = maximum * maximum * 1e-6;
        if (Math.abs(x.dot(y)) <= tolerance && Math.abs(x.dot(z)) <= tolerance && Math.abs(y.dot(z)) <= tolerance) return maximum;
        return Math.sqrt(one * infinity);
    }
}
