package lib.kasuga.registration.data_driven.shape;

import java.util.List;

/** Aggregated failures raised by the legacy builder adapter. */
public final class ShapeCompilationException extends IllegalArgumentException {
    private final List<String> failures;

    public ShapeCompilationException(List<String> failures) {
        super(String.join(System.lineSeparator(), failures));
        this.failures = List.copyOf(failures);
    }

    public List<String> failures() {
        return failures;
    }
}
