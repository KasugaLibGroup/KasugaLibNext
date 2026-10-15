package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

/** Indicates malformed or truncated VMD motion data. */
public class VmdFormatException extends RuntimeException {
    public VmdFormatException(String message) {
        super(message);
    }

    public VmdFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
