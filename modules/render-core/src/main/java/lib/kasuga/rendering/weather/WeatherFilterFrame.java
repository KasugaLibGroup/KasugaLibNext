package lib.kasuga.rendering.weather;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Immutable world-space placement of a {@link WeatherFilterVolume}.
 * Precomputes the unit-box inverse matrix and conservative world AABB.
 */
@Accessors(fluent = true)
public final class WeatherFilterFrame {
    private static final double MIN_SAFE_AXIS = 1e-12;

    @Getter
    private final WeatherFilterVolume volume;
    private final Matrix4d worldTransform;
    private final Matrix4d localToWorld;
    private final Matrix4d worldToUnitBox;
    @Getter
    private final double minX;
    @Getter
    private final double minY;
    @Getter
    private final double minZ;
    @Getter
    private final double maxX;
    @Getter
    private final double maxY;
    @Getter
    private final double maxZ;

    public WeatherFilterFrame(WeatherFilterVolume volume, Matrix4dc parentTransform) {
        this.volume = Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(parentTransform, "parentTransform");
        if (!parentTransform.isFinite() || !parentTransform.isAffine() || parentTransform.determinant() == 0.0) {
            throw new IllegalArgumentException("Weather filter transform must be finite, affine, and invertible");
        }
        this.worldTransform = new Matrix4d(parentTransform);
        this.localToWorld = new Matrix4d(this.worldTransform)
                .translate(volume.x(), volume.y(), volume.z())
                .rotateY(Math.toRadians(volume.yaw()))
                .rotateX(Math.toRadians(volume.pitch()))
                .rotateZ(Math.toRadians(volume.roll()))
                .scale(volume.sizeX(), volume.sizeY(), volume.sizeZ());
        if (!this.localToWorld.isFinite() || this.localToWorld.determinant() == 0.0) {
            throw new IllegalArgumentException("Weather filter local-to-world matrix must be finite and invertible");
        }
        this.worldToUnitBox = new Matrix4d(this.localToWorld).invert();
        if (!this.worldToUnitBox.isFinite()) {
            throw new IllegalArgumentException("Weather filter inverse matrix is not finite");
        }

        double loX = Double.POSITIVE_INFINITY;
        double loY = Double.POSITIVE_INFINITY;
        double loZ = Double.POSITIVE_INFINITY;
        double hiX = Double.NEGATIVE_INFINITY;
        double hiY = Double.NEGATIVE_INFINITY;
        double hiZ = Double.NEGATIVE_INFINITY;
        Vector3d corner = new Vector3d();
        for (int ix = -1; ix <= 1; ix += 2) {
            for (int iy = -1; iy <= 1; iy += 2) {
                for (int iz = -1; iz <= 1; iz += 2) {
                    this.localToWorld.transformPosition(ix * 0.5, iy * 0.5, iz * 0.5, corner);
                    if (corner.x < loX) loX = corner.x;
                    if (corner.y < loY) loY = corner.y;
                    if (corner.z < loZ) loZ = corner.z;
                    if (corner.x > hiX) hiX = corner.x;
                    if (corner.y > hiY) hiY = corner.y;
                    if (corner.z > hiZ) hiZ = corner.z;
                }
            }
        }
        this.minX = loX;
        this.minY = loY;
        this.minZ = loZ;
        this.maxX = hiX;
        this.maxY = hiY;
        this.maxZ = hiZ;
    }

    public static WeatherFilterFrame world(WeatherFilterVolume volume) {
        return new WeatherFilterFrame(volume, new Matrix4d());
    }

    public Matrix4d worldTransform() {
        return new Matrix4d(worldTransform);
    }

    public Matrix4d localToWorld() {
        return new Matrix4d(localToWorld);
    }

    public Matrix4d worldToUnitBox() {
        return new Matrix4d(worldToUnitBox);
    }

    public Vector3d center() {
        return new Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
    }

    public boolean intersectsHorizontal(double regionMinX, double regionMinZ, double regionMaxX, double regionMaxZ) {
        return maxX >= regionMinX && minX <= regionMaxX && maxZ >= regionMinZ && minZ <= regionMaxZ;
    }

    public boolean containsBounds(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public float occlusionAt(double worldX, double worldY, double worldZ) {
        if (volume.strength() <= 0.0f || !containsBounds(worldX, worldY, worldZ)) {
            return 0.0f;
        }
        Vector3d local = worldToUnitBox.transformPosition(worldX, worldY, worldZ, new Vector3d());
        double maxAxis = Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z)));
        if (maxAxis > 0.5) {
            return 0.0f;
        }
        float edgeWeight = 1.0f;
        float feather = volume.feather();
        if (feather > 1e-4f) {
            edgeWeight = (float) Math.clamp((0.5 - maxAxis) / feather, 0.0, 1.0);
        }
        return volume.strength() * edgeWeight;
    }

    /**
     * Clips a vertical segment {@code (worldX, maxY..minY, worldZ)} from top to bottom against
     * this OBB when it acts as a full-strength occlusion zone. Returns the highest world Y
     * intersected within {@code [minY, maxY]}.
     */
    public OptionalDouble clipVertical(double worldX, double minY, double maxY, double worldZ) {
        if (volume.strength() < 0.999f || maxY <= minY) {
            return OptionalDouble.empty();
        }
        if (worldX < this.minX || worldX > this.maxX || worldZ < this.minZ || worldZ > this.maxZ
                || maxY < this.minY || minY > this.maxY) {
            return OptionalDouble.empty();
        }
        Vector3d start = worldToUnitBox.transformPosition(worldX, maxY, worldZ, new Vector3d());
        Vector3d end = worldToUnitBox.transformPosition(worldX, minY, worldZ, new Vector3d());
        Vector3d delta = end.sub(start, new Vector3d());

        double tMin = 0.0;
        double tMax = 1.0;

        for (int axis = 0; axis < 3; axis++) {
            double s = start.get(axis);
            double d = delta.get(axis);
            if (Math.abs(d) < MIN_SAFE_AXIS) {
                if (s < -0.5 || s > 0.5) {
                    return OptionalDouble.empty();
                }
                continue;
            }
            double invD = 1.0 / d;
            double t1 = (-0.5 - s) * invD;
            double t2 = (0.5 - s) * invD;
            double lo = Math.min(t1, t2);
            double hi = Math.max(t1, t2);
            if (lo > tMin) tMin = lo;
            if (hi < tMax) tMax = hi;
            if (tMin > tMax) {
                return OptionalDouble.empty();
            }
        }
        double hitY = maxY + tMin * (minY - maxY);
        return OptionalDouble.of(Math.clamp(hitY, minY, maxY));
    }
}
