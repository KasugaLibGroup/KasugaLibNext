package lib.kasuga.registration.data_driven.pipeline;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * One structured diagnostic emitted by preparation, validation, apply, or finalization.
 */
public record BuildDiagnostic(
        Severity severity,
        Stage stage,
        Code code,
        @Nullable String sourceId,
        @Nullable String ownerId,
        @Nullable String logicalPath,
        @Nullable String jsonPointer,
        @Nullable String handler,
        String message,
        @Nullable String suggestion
) {
    public BuildDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) throw new IllegalArgumentException("message must not be blank");
    }

    public boolean blocking() {
        return severity == Severity.ERROR;
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public enum Stage {
        SOURCE,
        MANIFEST,
        PARSE,
        SELECTION,
        VALIDATION,
        CLAIMS,
        APPLY,
        FINALIZE
    }

    public enum Code {
        INVALID_SOURCE,
        UNKNOWN_DEPENDENCY,
        MANIFEST_INVALID,
        MANIFEST_EMPTY,
        INVALID_PATH,
        SOURCE_FILE_MISSING,
        SOURCE_READ_FAILED,
        CONTENT_INVALID,
        UNKNOWN_TYPE,
        PARSE_FAILED,
        IDENTITY_FAILED,
        MISSING_IDENTITY,
        HANDLER_NOT_STRICT_READY,
        INVALID_IDENTITY,
        UNKNOWN_FACTORY,
        UNKNOWN_REGISTRY_GROUP,
        MISSING_REFERENCE,
        REFERENCE_NOT_DECLARED,
        GROUP_CYCLE,
        VALIDATION_FAILED,
        CLAIM_CONFLICT,
        CLAIM_FAILED,
        APPLY_FAILED,
        FINALIZER_FAILED
    }
}
