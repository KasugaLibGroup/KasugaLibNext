package lib.kasuga.registration.data_driven.source;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A path relative to a content source root.
 *
 * <p>The value is slash-separated and is deliberately kept literal. Invalid paths are rejected
 * instead of normalized so two spellings can never silently refer to the same source file.
 */
public record LogicalPath(String value) implements Comparable<LogicalPath> {
    private static final Pattern WINDOWS_DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*$");

    public LogicalPath {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("logical path must not be blank");
        }
        if (value.startsWith("/")) {
            throw new IllegalArgumentException("logical path must be relative: '" + value + "'");
        }
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("logical path must use '/' separators: '" + value + "'");
        }
        if (WINDOWS_DRIVE_PREFIX.matcher(value).matches()) {
            throw new IllegalArgumentException("logical path must not contain a drive prefix: '" + value + "'");
        }

        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("logical path contains an empty segment: '" + value + "'");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("logical path contains a traversal segment: '" + value + "'");
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character == 0 || Character.isISOControl(character)) {
                    throw new IllegalArgumentException("logical path contains a control character: '" + value + "'");
                }
            }
        }
    }

    public static LogicalPath of(String value) {
        return new LogicalPath(value);
    }

    public List<String> segments() {
        return List.copyOf(Arrays.asList(value.split("/", -1)));
    }

    /**
     * Resolves this path below {@code root} and verifies the result stays below that root.
     */
    public Path resolve(Path root) {
        Objects.requireNonNull(root, "root");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot;
        for (String segment : segments()) {
            resolved = resolved.resolve(segment);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("logical path escapes its source root: '" + value + "'");
        }
        return resolved;
    }

    @Override
    public int compareTo(LogicalPath other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
