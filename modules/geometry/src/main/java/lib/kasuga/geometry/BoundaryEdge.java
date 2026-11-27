package lib.kasuga.geometry;

/**
 * One merged, axis-aligned edge on the final exterior or hole boundary.
 *
 * <p>Endpoint order is canonicalized so the same edge has one value regardless of which face
 * produced it.</p>
 */
public record BoundaryEdge(
        float startX,
        float startY,
        float startZ,
        float endX,
        float endY,
        float endZ
) {
    public BoundaryEdge {
        startX = GeometrySupport.blockCoordinate(startX, "startX");
        startY = GeometrySupport.blockCoordinate(startY, "startY");
        startZ = GeometrySupport.blockCoordinate(startZ, "startZ");
        endX = GeometrySupport.blockCoordinate(endX, "endX");
        endY = GeometrySupport.blockCoordinate(endY, "endY");
        endZ = GeometrySupport.blockCoordinate(endZ, "endZ");

        int differingAxes = (Float.compare(startX, endX) != 0 ? 1 : 0)
                + (Float.compare(startY, endY) != 0 ? 1 : 0)
                + (Float.compare(startZ, endZ) != 0 ? 1 : 0);
        if (differingAxes != 1) {
            throw new GeometryException("boundary edge must vary on exactly one axis");
        }

        if (comparePoint(endX, endY, endZ, startX, startY, startZ) < 0) {
            float x = startX;
            float y = startY;
            float z = startZ;
            startX = endX;
            startY = endY;
            startZ = endZ;
            endX = x;
            endY = y;
            endZ = z;
        }
    }

    public BoundaryEdge(Point start, Point end) {
        this(start.x(), start.y(), start.z(), end.x(), end.y(), end.z());
    }

    public Point start() {
        return new Point(startX, startY, startZ);
    }

    public Point end() {
        return new Point(endX, endY, endZ);
    }

    public Axis axis() {
        if (Float.compare(startX, endX) != 0) return Axis.X;
        if (Float.compare(startY, endY) != 0) return Axis.Y;
        return Axis.Z;
    }

    public float length() {
        return switch (axis()) {
            case X -> endX - startX;
            case Y -> endY - startY;
            case Z -> endZ - startZ;
        };
    }

    private static int comparePoint(float ax, float ay, float az, float bx, float by, float bz) {
        int x = Float.compare(ax, bx);
        if (x != 0) return x;
        int y = Float.compare(ay, by);
        if (y != 0) return y;
        return Float.compare(az, bz);
    }
}
