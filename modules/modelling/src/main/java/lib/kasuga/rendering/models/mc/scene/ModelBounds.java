package lib.kasuga.rendering.models.mc.scene;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Immutable local bounds in Minecraft blocks (+Y up), including display displacement. */
public record ModelBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public ModelBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ)
            throw new IllegalArgumentException("Model bounds must be finite and ordered");
    }

    public Vector3f minimum() { return new Vector3f((float) minX, (float) minY, (float) minZ); }
    public Vector3f maximum() { return new Vector3f((float) maxX, (float) maxY, (float) maxZ); }
    public double minimum(LayerLayout.Axis axis) { return switch (axis) { case X -> minX; case Y -> minY; case Z -> minZ; }; }
    public ModelBounds union(ModelBounds other) {
        return new ModelBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }
    public ModelBounds move(LayerLayout.Axis axis, double value) {
        return move(axis == LayerLayout.Axis.X ? value : 0, axis == LayerLayout.Axis.Y ? value : 0,
                axis == LayerLayout.Axis.Z ? value : 0);
    }
    public ModelBounds move(double x, double y, double z) {
        return new ModelBounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }
    public ModelBounds transform(Matrix4fc transform) {
        if (!transform.isFinite() || !transform.isAffine()) throw new IllegalArgumentException("Expected finite affine transform");
        ModelBounds result = null;
        for (int i = 0; i < 8; i++) {
            Vector3f p = transform.transformPosition(new Vector3f((float) ((i & 1) == 0 ? minX : maxX),
                    (float) ((i & 2) == 0 ? minY : maxY), (float) ((i & 4) == 0 ? minZ : maxZ)));
            var point = new ModelBounds(p.x, p.y, p.z, p.x, p.y, p.z);
            result = result == null ? point : result.union(point);
        }
        return result;
    }
}
