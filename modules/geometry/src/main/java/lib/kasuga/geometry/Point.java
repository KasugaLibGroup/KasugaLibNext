package lib.kasuga.geometry;

/** An immutable point in block-local float coordinates. */
public record Point(float x, float y, float z) {
    public Point {
        x = GeometrySupport.blockCoordinate(x, "x");
        y = GeometrySupport.blockCoordinate(y, "y");
        z = GeometrySupport.blockCoordinate(z, "z");
    }
}
