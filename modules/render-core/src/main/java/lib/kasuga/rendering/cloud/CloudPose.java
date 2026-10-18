package lib.kasuga.rendering.cloud;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.FrustumIntersection;

/** Position is the center of the flat cloud base; yaw is in degrees, dimensions in world units. */
public record CloudPose(double x, double y, double z, float width, float height, float depth, float yaw) {
    public CloudPose {
        finite(x); finite(y); finite(z); finite(yaw);
        CloudSettings.range(width, .01f, 1_000_000, "width");
        CloudSettings.range(height, .01f, 1_000_000, "height");
        CloudSettings.range(depth, .01f, 1_000_000, "depth");
    }

    public static CloudPose at(double x, double y, double z) { return new CloudPose(x, y, z, 1600, 1200, 1200, 0); }
    public CloudPose moveTo(double x, double y, double z) { return new CloudPose(x, y, z, width, height, depth, yaw); }
    public CloudPose moveBy(double x, double y, double z) { return moveTo(this.x + x, this.y + y, this.z + z); }
    public CloudPose withYaw(float value) { return new CloudPose(x, y, z, width, height, depth, value); }
    public CloudPose sized(float w, float h, float d) { return new CloudPose(x, y, z, w, h, d, yaw); }
    public CloudPose scale(float x, float y, float z) {
        CloudSettings.range(x, .0001f, 1_000_000, "scale x");
        CloudSettings.range(y, .0001f, 1_000_000, "scale y");
        CloudSettings.range(z, .0001f, 1_000_000, "scale z");
        return sized(width * x, height * y, depth * z);
    }

    /** Subtract in double precision before the GPU receives camera-relative coordinates. */
    public Matrix4f worldToLocal(double cameraX, double cameraY, double cameraZ) {
        return new Matrix4f().translation((float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ))
                .rotateY((float) Math.toRadians(yaw)).scale(width * .5f, height, depth * .5f).invert();
    }

    public Bounds bounds() {
        double cos = Math.abs(Math.cos(Math.toRadians(yaw))), sin = Math.abs(Math.sin(Math.toRadians(yaw)));
        double rx = (cos * width + sin * depth) * .5, rz = (sin * width + cos * depth) * .5;
        return new Bounds(x - rx, y, z - rz, x + rx, y + height, z + rz);
    }

    /** Cull sky volumes against the near and side planes, independently of terrain draw distance. */
    public boolean isVisible(Matrix4fc viewProjection, double cameraX, double cameraY, double cameraZ) {
        var localToClip = new Matrix4f(viewProjection).mul(worldToLocal(cameraX, cameraY, cameraZ).invert());
        var frustum = new FrustumIntersection(localToClip);
        int mask = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
                | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY | FrustumIntersection.PLANE_MASK_NZ;
        return frustum.intersectAab(-1, 0, -1, 1, 1, 1, mask) < 0;
    }

    CloudPose interpolate(CloudPose other, float fraction) {
        return new CloudPose(x + (other.x - x) * fraction, y + (other.y - y) * fraction,
                z + (other.z - z) * fraction, width + (other.width - width) * fraction,
                height + (other.height - height) * fraction, depth + (other.depth - depth) * fraction,
                yaw + (other.yaw - yaw) * fraction);
    }

    static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Cloud coordinates must be finite");
    }
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
}
