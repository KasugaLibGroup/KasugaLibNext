package lib.kasuga.shader.graal;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;

/** A JavaScript or DSL-construction failure with the original source and cause. */
public final class ShaderScriptException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The original diagnostic source name. */
    private final String sourceName;
    /** One-based source line, or zero when unavailable. */
    private final int line;
    /** One-based source column, or zero when unavailable. */
    private final int column;

    ShaderScriptException(String sourceName, int line, int column, String message, Throwable cause) {
        super(sourceName + (line > 0 ? ":" + line + ":" + column : "") + ": " + message, cause);
        this.sourceName = sourceName;
        this.line = line;
        this.column = column;
    }

    static ShaderScriptException from(String sourceName, PolyglotException cause) {
        SourceSection location = cause.getSourceLocation();
        if (location == null || !location.getSource().getName().equals(sourceName)) {
            for (var frame : cause.getPolyglotStackTrace()) {
                SourceSection candidate = frame.getSourceLocation();
                if (frame.isGuestFrame() && candidate != null
                        && candidate.getSource().getName().equals(sourceName)) {
                    location = candidate;
                    break;
                }
            }
        }
        boolean located = location != null && location.getSource().getName().equals(sourceName);
        return new ShaderScriptException(sourceName,
                located ? location.getStartLine() : 0,
                located ? location.getStartColumn() : 0,
                cause.getMessage(), cause);
    }

    /**
     * Identifies the definition that failed.
     * @return the file path or source name supplied to the compiler
     */
    public String sourceName() { return sourceName; }

    /**
     * Locates the failure's line in the original source.
     * @return the one-based line, or zero when no source location is available
     */
    public int line() { return line; }

    /**
     * Locates the failure's column in the original source.
     * @return the one-based column, or zero when no source location is available
     */
    public int column() { return column; }
}
