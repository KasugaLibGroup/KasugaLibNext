package lib.kasuga.geometry;

import java.util.Objects;

/**
 * The geometry-only part of a shape reference transform.
 *
 * <p>Order is positive per-axis scale around the pivot, quarter-turn around Y, then translation.
 * Block-unit inputs use {@link #ofBlocks}; authoring-pixel pivot and translation inputs use
 * {@link #fromPixels}.</p>
 */
public final class Transform {
    public static final float DEFAULT_PIVOT_X = 0.5f;
    public static final float DEFAULT_PIVOT_Y = 0.0f;
    public static final float DEFAULT_PIVOT_Z = 0.5f;

    private final float scaleX;
    private final float scaleY;
    private final float scaleZ;
    private final int rotationY;
    private final float pivotX;
    private final float pivotY;
    private final float pivotZ;
    private final float translateX;
    private final float translateY;
    private final float translateZ;

    private Transform(
            float scaleX,
            float scaleY,
            float scaleZ,
            int rotationY,
            float pivotX,
            float pivotY,
            float pivotZ,
            float translateX,
            float translateY,
            float translateZ
    ) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
        this.rotationY = validateRotation(rotationY);
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.pivotZ = pivotZ;
        this.translateX = translateX;
        this.translateY = translateY;
        this.translateZ = translateZ;
    }

    public static Transform identity() {
        return new Transform(
                1.0f, 1.0f, 1.0f, 0,
                DEFAULT_PIVOT_X, DEFAULT_PIVOT_Y, DEFAULT_PIVOT_Z,
                0.0f, 0.0f, 0.0f
        );
    }

    public static Transform ofBlocks(
            float scaleX,
            float scaleY,
            float scaleZ,
            int rotationY,
            float pivotX,
            float pivotY,
            float pivotZ,
            float translateX,
            float translateY,
            float translateZ
    ) {
        return new Transform(
                requirePositiveScale(scaleX, "scaleX"),
                requirePositiveScale(scaleY, "scaleY"),
                requirePositiveScale(scaleZ, "scaleZ"),
                rotationY,
                blockCoordinate(pivotX, "pivotX"),
                blockCoordinate(pivotY, "pivotY"),
                blockCoordinate(pivotZ, "pivotZ"),
                blockCoordinate(translateX, "translateX"),
                blockCoordinate(translateY, "translateY"),
                blockCoordinate(translateZ, "translateZ")
        );
    }

    public static Transform fromPixels(
            double scaleX,
            double scaleY,
            double scaleZ,
            int rotationY,
            double pivotX,
            double pivotY,
            double pivotZ,
            double translateX,
            double translateY,
            double translateZ
    ) {
        return new Transform(
                GeometrySupport.checkedScale(scaleX, "scaleX"),
                GeometrySupport.checkedScale(scaleY, "scaleY"),
                GeometrySupport.checkedScale(scaleZ, "scaleZ"),
                rotationY,
                GeometrySupport.pixelToBlock(pivotX, "pivotX"),
                GeometrySupport.pixelToBlock(pivotY, "pivotY"),
                GeometrySupport.pixelToBlock(pivotZ, "pivotZ"),
                GeometrySupport.pixelToBlock(translateX, "translateX"),
                GeometrySupport.pixelToBlock(translateY, "translateY"),
                GeometrySupport.pixelToBlock(translateZ, "translateZ")
        );
    }

    public Shape apply(Shape shape) {
        return Objects.requireNonNull(shape, "shape").transform(this);
    }

    public Shape apply(Shape shape, GeometryBudget budget) {
        return Objects.requireNonNull(shape, "shape").transform(this, budget);
    }

    Box apply(Box box) {
        Sample[] corners = {
                transformPoint(box.minX(), box.minY(), box.minZ()),
                transformPoint(box.minX(), box.minY(), box.maxZ()),
                transformPoint(box.minX(), box.maxY(), box.minZ()),
                transformPoint(box.minX(), box.maxY(), box.maxZ()),
                transformPoint(box.maxX(), box.minY(), box.minZ()),
                transformPoint(box.maxX(), box.minY(), box.maxZ()),
                transformPoint(box.maxX(), box.maxY(), box.minZ()),
                transformPoint(box.maxX(), box.maxY(), box.maxZ())
        };
        float minX = corners[0].x;
        float minY = corners[0].y;
        float minZ = corners[0].z;
        float maxX = corners[0].x;
        float maxY = corners[0].y;
        float maxZ = corners[0].z;
        for (int index = 1; index < corners.length; index++) {
            Sample corner = corners[index];
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        try {
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (GeometryException degeneracy) {
            throw new GeometryException("transform produced a degenerate or out-of-range box", degeneracy);
        }
    }

    private Sample transformPoint(float x, float y, float z) {
        double scaledX = pivotX + ((double) x - pivotX) * scaleX;
        double scaledY = pivotY + ((double) y - pivotY) * scaleY;
        double scaledZ = pivotZ + ((double) z - pivotZ) * scaleZ;

        double offsetX = scaledX - pivotX;
        double offsetZ = scaledZ - pivotZ;
        double rotatedX;
        double rotatedZ;
        switch (rotationY) {
            case 0 -> {
                rotatedX = offsetX;
                rotatedZ = offsetZ;
            }
            case 90 -> {
                rotatedX = -offsetZ;
                rotatedZ = offsetX;
            }
            case 180 -> {
                rotatedX = -offsetX;
                rotatedZ = -offsetZ;
            }
            case 270 -> {
                rotatedX = offsetZ;
                rotatedZ = -offsetX;
            }
            default -> throw new IllegalStateException("validated rotation");
        }

        return new Sample(
                GeometrySupport.finiteResult(pivotX + rotatedX + translateX, "transformed X"),
                GeometrySupport.finiteResult(scaledY + translateY, "transformed Y"),
                GeometrySupport.finiteResult(pivotZ + rotatedZ + translateZ, "transformed Z")
        );
    }

    public float scaleX() {
        return scaleX;
    }

    public float scaleY() {
        return scaleY;
    }

    public float scaleZ() {
        return scaleZ;
    }

    public int rotationY() {
        return rotationY;
    }

    public float pivotX() {
        return pivotX;
    }

    public float pivotY() {
        return pivotY;
    }

    public float pivotZ() {
        return pivotZ;
    }

    public float translateX() {
        return translateX;
    }

    public float translateY() {
        return translateY;
    }

    public float translateZ() {
        return translateZ;
    }

    private static int validateRotation(int rotationY) {
        if (rotationY != 0 && rotationY != 90 && rotationY != 180 && rotationY != 270) {
            throw new GeometryException("rotationY must be one of 0, 90, 180, or 270 degrees");
        }
        return rotationY;
    }

    private static float requirePositiveScale(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new GeometryException(name + " must be finite and positive: " + value);
        }
        return value;
    }

    private static float blockCoordinate(float value, String name) {
        return GeometrySupport.blockCoordinate(value, name);
    }

    private record Sample(float x, float y, float z) {
    }
}
