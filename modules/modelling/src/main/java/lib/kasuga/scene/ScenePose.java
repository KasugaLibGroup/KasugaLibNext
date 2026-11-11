package lib.kasuga.scene;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

import java.util.Objects;

/** A world origin in doubles and an origin-relative affine transform. No camera is baked in. */
public record ScenePose(Vec3 origin, Matrix4f transform) {
    public ScenePose(Vec3 origin, Matrix4fc transform) { this(origin, new Matrix4f(transform)); }

    public ScenePose {
        Objects.requireNonNull(origin);
        Objects.requireNonNull(transform);
        if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)
                || !transform.isFinite() || !transform.isAffine() || transform.determinant() == 0
                || !new Matrix4f(transform).invert().isFinite()) {
            throw new IllegalArgumentException("Scene pose must be finite, affine and invertible");
        }
        // Fold local translation into the double origin before constructing camera-relative floats.
        origin = origin.add(transform.m30(), transform.m31(), transform.m32());
        transform = new Matrix4f(transform).setTranslation(0, 0, 0);
    }

    @Override public Matrix4f transform() { return new Matrix4f(transform); }

    public Matrix4f relativeTo(Vec3 camera) {
        return new Matrix4f().translation((float) (origin.x - camera.x), (float) (origin.y - camera.y),
                (float) (origin.z - camera.z)).mul(transform);
    }

    /** Places a child in this host's local frame, applying the host rotation exactly once. */
    public ScenePose compose(Vec3 localOrigin, Matrix4fc localTransform) {
        return new ScenePose(toWorld(localOrigin), new Matrix4f(transform).mul(localTransform));
    }

    public Vec3 toWorld(Vec3 local) {
        var result = new Matrix4d(transform).transformPosition(new Vector3d(local.x, local.y, local.z));
        return origin.add(result.x, result.y, result.z);
    }

    public Vec3 toLocal(Vec3 world) {
        var result = new Matrix4d(transform).invert().transformPosition(
                new Vector3d(world.x - origin.x, world.y - origin.y, world.z - origin.z));
        return new Vec3(result.x, result.y, result.z);
    }
}
