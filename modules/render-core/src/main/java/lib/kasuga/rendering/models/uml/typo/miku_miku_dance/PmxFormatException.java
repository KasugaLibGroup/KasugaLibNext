package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

/** Indicates malformed or unsupported PMX binary data. */
public class PmxFormatException extends IllegalArgumentException {
    public PmxFormatException(String message) {
        super(message);
    }

    public PmxFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
