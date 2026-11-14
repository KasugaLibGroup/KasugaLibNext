package lib.kasuga.registration.data_driven.source;

import java.io.IOException;
import java.util.Optional;

/**
 * Typed failure raised while opening or validating a content source.
 */
public final class SourceAccessException extends IOException {
    public enum Reason {
        NOT_FOUND,
        ACCESS_DENIED,
        OUT_OF_BOUNDS,
        SYMLINK_REJECTED,
        DUPLICATE_ENTRY,
        INVALID_ENTRY,
        LIMIT_EXCEEDED,
        CORRUPT_ARCHIVE,
        SOURCE_CLOSED,
        IO_ERROR
    }

    private final Reason reason;
    private final String sourceId;
    private final LogicalPath logicalPath;

    public SourceAccessException(
            Reason reason,
            SourceDescriptor source,
            LogicalPath logicalPath,
            String message
    ) {
        this(reason, source.sourceId(), logicalPath, message, null, source.diagnosticLabel());
    }

    public SourceAccessException(
            Reason reason,
            SourceDescriptor source,
            LogicalPath logicalPath,
            String message,
            Throwable cause
    ) {
        this(reason, source.sourceId(), logicalPath, message, cause, source.diagnosticLabel());
    }

    public SourceAccessException(
            Reason reason,
            String sourceId,
            LogicalPath logicalPath,
            String message,
            Throwable cause
    ) {
        this(reason, sourceId, logicalPath, message, cause, sourceId);
    }

    public SourceAccessException(
            Reason reason,
            String sourceId,
            LogicalPath logicalPath,
            String message
    ) {
        this(reason, sourceId, logicalPath, message, null, sourceId);
    }

    private SourceAccessException(
            Reason reason,
            String sourceId,
            LogicalPath logicalPath,
            String message,
            Throwable cause,
            String label
    ) {
        super("[" + label + "] " + message, cause);
        this.reason = reason;
        this.sourceId = sourceId;
        this.logicalPath = logicalPath;
    }

    public Reason reason() {
        return reason;
    }

    public String sourceId() {
        return sourceId;
    }

    public Optional<LogicalPath> logicalPath() {
        return Optional.ofNullable(logicalPath);
    }
}
