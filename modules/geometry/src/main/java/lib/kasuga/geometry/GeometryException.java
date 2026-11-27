package lib.kasuga.geometry;

/** A geometry value cannot be represented without overflowing or becoming degenerate. */
public class GeometryException extends IllegalArgumentException {
    public GeometryException(String message) {
        super(message);
    }

    public GeometryException(String message, Throwable cause) {
        super(message, cause);
    }
}
