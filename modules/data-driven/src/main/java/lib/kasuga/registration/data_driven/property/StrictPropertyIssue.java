package lib.kasuga.registration.data_driven.property;

import java.util.Objects;

/** One side-effect-free strict property validation failure. */
public record StrictPropertyIssue(String relativeJsonPointer, String message) {
    public StrictPropertyIssue {
        Objects.requireNonNull(relativeJsonPointer, "relativeJsonPointer");
        Objects.requireNonNull(message, "message");
    }
}
