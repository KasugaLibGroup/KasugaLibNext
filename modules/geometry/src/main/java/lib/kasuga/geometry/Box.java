package lib.kasuga.geometry;

/**
 * One immutable axis-aligned solid in block units.
 *
 * <p>Every extent is positive. Use {@link #fromPixels} for authoring coordinates.</p>
 */
public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    public Box {
        minX = GeometrySupport.blockCoordinate(minX, "minX");
        minY = GeometrySupport.blockCoordinate(minY, "minY");
        minZ = GeometrySupport.blockCoordinate(minZ, "minZ");
        maxX = GeometrySupport.blockCoordinate(maxX, "maxX");
        maxY = GeometrySupport.blockCoordinate(maxY, "maxY");
        maxZ = GeometrySupport.blockCoordinate(maxZ, "maxZ");
        GeometrySupport.validateBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Converts six authoring-pixel values to block units after checking raw range and binary32
     * narrowing. Degenerate values are rejected instead of being expanded.
     */
    public static Box fromPixels(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        validateRawPixels(minX, minY, minZ, maxX, maxY, maxZ);
        float narrowMinX = narrowPixel(minX, "minX");
        float narrowMinY = narrowPixel(minY, "minY");
        float narrowMinZ = narrowPixel(minZ, "minZ");
        float narrowMaxX = narrowPixel(maxX, "maxX");
        float narrowMaxY = narrowPixel(maxY, "maxY");
        float narrowMaxZ = narrowPixel(maxZ, "maxZ");
        GeometrySupport.validateBox(narrowMinX, narrowMinY, narrowMinZ,
                narrowMaxX, narrowMaxY, narrowMaxZ);
        return new Box(
                GeometrySupport.pixelToBlock(narrowMinX, "minX"),
                GeometrySupport.pixelToBlock(narrowMinY, "minY"),
                GeometrySupport.pixelToBlock(narrowMinZ, "minZ"),
                GeometrySupport.pixelToBlock(narrowMaxX, "maxX"),
                GeometrySupport.pixelToBlock(narrowMaxY, "maxY"),
                GeometrySupport.pixelToBlock(narrowMaxZ, "maxZ")
        );
    }

    /** Volume in cubic blocks. */
    public double volume() {
        return (double) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    /** Closed-boundary membership in a regularized solid. */
    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(Point point) {
        return contains(point.x(), point.y(), point.z());
    }

    public boolean overlapsVolume(Box other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    public Bounds bounds() {
        return Bounds.of(this);
    }

    private static void validateRawPixels(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        validateRawPixel(minX, "minX");
        validateRawPixel(minY, "minY");
        validateRawPixel(minZ, "minZ");
        validateRawPixel(maxX, "maxX");
        validateRawPixel(maxY, "maxY");
        validateRawPixel(maxZ, "maxZ");
        GeometrySupport.requirePositive((float) minX, (float) maxX, "X");
        GeometrySupport.requirePositive((float) minY, (float) maxY, "Y");
        GeometrySupport.requirePositive((float) minZ, (float) maxZ, "Z");
    }

    private static void validateRawPixel(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > GeometrySupport.MAX_PIXEL_COORDINATE) {
            throw new GeometryException(name + " must be finite and within +/-"
                    + GeometrySupport.MAX_PIXEL_COORDINATE + " pixels: " + value);
        }
    }

    private static float narrowPixel(double value, String name) {
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed) || Math.abs(narrowed) > GeometrySupport.MAX_PIXEL_COORDINATE) {
            throw new GeometryException(name + " cannot be represented as a supported float pixel: "
                    + value);
        }
        return GeometrySupport.normalizeZero(narrowed);
    }
}
