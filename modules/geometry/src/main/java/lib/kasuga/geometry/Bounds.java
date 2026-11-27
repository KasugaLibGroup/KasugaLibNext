package lib.kasuga.geometry;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * The total extent of a shape.
 *
 * <p>This type is a broad-phase bound. It is not a faithful result for holes or disconnected
 * regions; use {@link Shape#boxes()} for that.</p>
 */
public final class Bounds {
    private static final Bounds EMPTY = new Bounds();

    private final boolean empty;
    private final float minX;
    private final float minY;
    private final float minZ;
    private final float maxX;
    private final float maxY;
    private final float maxZ;

    private Bounds() {
        empty = true;
        minX = minY = minZ = maxX = maxY = maxZ = 0.0f;
    }

    private Bounds(Box box) {
        empty = false;
        minX = box.minX();
        minY = box.minY();
        minZ = box.minZ();
        maxX = box.maxX();
        maxY = box.maxY();
        maxZ = box.maxZ();
    }

    private Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        empty = false;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static Bounds empty() {
        return EMPTY;
    }

    public static Bounds of(Box... boxes) {
        Objects.requireNonNull(boxes, "boxes");
        if (boxes.length == 0) {
            return EMPTY;
        }
        float minX = boxes[0].minX();
        float minY = boxes[0].minY();
        float minZ = boxes[0].minZ();
        float maxX = boxes[0].maxX();
        float maxY = boxes[0].maxY();
        float maxZ = boxes[0].maxZ();
        for (int index = 1; index < boxes.length; index++) {
            Box box = boxes[index];
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Bounds of(Collection<Box> boxes) {
        Objects.requireNonNull(boxes, "boxes");
        return of(boxes.toArray(Box[]::new));
    }

    public boolean isEmpty() {
        return empty;
    }

    public float minX() {
        requirePresent();
        return minX;
    }

    public float minY() {
        requirePresent();
        return minY;
    }

    public float minZ() {
        requirePresent();
        return minZ;
    }

    public float maxX() {
        requirePresent();
        return maxX;
    }

    public float maxY() {
        requirePresent();
        return maxY;
    }

    public float maxZ() {
        requirePresent();
        return maxZ;
    }

    /** The total extent as a box, or empty for an empty shape. */
    public Optional<Box> asBox() {
        return empty ? Optional.empty() : Optional.of(new Box(minX, minY, minZ, maxX, maxY, maxZ));
    }

    public boolean contains(float x, float y, float z) {
        return !empty && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** Volume of the total extent in cubic blocks. */
    public double volume() {
        if (empty) return 0.0;
        return (double) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    private void requirePresent() {
        if (empty) {
            throw new IllegalStateException("empty bounds have no coordinate");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Bounds bounds)) return false;
        if (empty != bounds.empty) return false;
        return empty || (Float.compare(minX, bounds.minX) == 0
                && Float.compare(minY, bounds.minY) == 0
                && Float.compare(minZ, bounds.minZ) == 0
                && Float.compare(maxX, bounds.maxX) == 0
                && Float.compare(maxY, bounds.maxY) == 0
                && Float.compare(maxZ, bounds.maxZ) == 0);
    }

    @Override
    public int hashCode() {
        return empty ? 0 : Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return empty ? "Bounds[empty]" : "Bounds[" + minX + ", " + minY + ", " + minZ
                + " -> " + maxX + ", " + maxY + ", " + maxZ + ']';
    }
}
