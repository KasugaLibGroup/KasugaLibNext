package lib.kasuga.core.vfs;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, platform-independent, normalized logical path within a {@link VirtualFileSystem}.
 * Always uses {@code /} as separator, omits leading/trailing slashes, and rejects traversal.
 * Physical backends additionally enforce {@link #requirePortable()} before resolving
 * a logical path on disk; archive names such as {@code con.json} remain addressable.
 */
public final class VfsPath implements Comparable<VfsPath> {
    public static final VfsPath ROOT = new VfsPath(List.of(), "");

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private final List<String> segments;
    private final String normalized;

    private VfsPath(List<String> segments, String normalized) {
        this.segments = segments;
        this.normalized = normalized;
    }

    public static VfsPath of(String rawPath) {
        if (rawPath == null) {
            throw new VfsInvalidPathException("VFS path must not be null");
        }
        checkGlobalForbiddenChars(rawPath);

        int start = 0;
        int end = rawPath.length();
        while (start < end && rawPath.charAt(start) == '/') {
            start++;
        }
        while (end > start && rawPath.charAt(end - 1) == '/') {
            end--;
        }
        if (start >= end) {
            return ROOT;
        }

        String trimmed = rawPath.substring(start, end);
        String[] parts = trimmed.split("/", -1);
        return buildFromValidatedSegments(parts);
    }

    public static VfsPath ofSegments(List<String> rawSegments) {
        if (rawSegments == null) {
            throw new VfsInvalidPathException("Segments list must not be null");
        }
        if (rawSegments.isEmpty()) {
            return ROOT;
        }
        List<String> validated = new ArrayList<>(rawSegments.size());
        for (String segment : rawSegments) {
            validateSegment(segment);
            validated.add(segment);
        }
        return new VfsPath(List.copyOf(validated), String.join("/", validated));
    }

    public static VfsPath fromOsRelativePath(Path relativePath) {
        if (relativePath == null) {
            throw new VfsInvalidPathException("Relative path must not be null");
        }
        if (relativePath.isAbsolute()) {
            throw new VfsInvalidPathException("OS path must be relative: " + relativePath);
        }
        if (relativePath.getNameCount() == 1 && relativePath.getName(0).toString().isEmpty()) {
            return ROOT;
        }
        List<String> segs = new ArrayList<>(relativePath.getNameCount());
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            String seg = relativePath.getName(i).toString();
            validateSegment(seg);
            segs.add(seg);
        }
        return new VfsPath(List.copyOf(segs), String.join("/", segs));
    }

    public static void validateSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new VfsInvalidPathException("VFS path segment must not be empty");
        }
        checkGlobalForbiddenChars(segment);
        if (segment.indexOf('/') >= 0) {
            throw new VfsInvalidPathException("VFS path segment must not contain '/': " + segment);
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw new VfsInvalidPathException("Path traversal segment is forbidden: " + segment);
        }
    }

    /** Validates portable disk names without restricting the namespace of read-only archives. */
    public VfsPath requirePortable() {
        for (String segment : segments) {
            validatePortableSegment(segment);
        }
        return this;
    }

    private static void validatePortableSegment(String segment) {
        validateSegment(segment);
        for (char c : new char[]{'<', '>', '"', '|', '?', '*'}) {
            if (segment.indexOf(c) >= 0) {
                throw new VfsInvalidPathException("Character forbidden in portable disk path: " + segment);
            }
        }
        char last = segment.charAt(segment.length() - 1);
        if (last == '.' || last == ' ') {
            throw new VfsInvalidPathException("Segment must not end with '.' or space for Windows compatibility: " + segment);
        }
        int dotIdx = segment.indexOf('.');
        String stem = (dotIdx >= 0 ? segment.substring(0, dotIdx) : segment).toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(stem)) {
            throw new VfsInvalidPathException("Windows reserved device name is forbidden in VFS path: " + segment);
        }
    }

    private static void checkGlobalForbiddenChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new VfsInvalidPathException("Control or NUL character forbidden in VFS path");
            }
            if (c == '\\') {
                throw new VfsInvalidPathException("Backslash '\\' is forbidden in VFS path (use '/'): " + value);
            }
            if (c == ':') {
                throw new VfsInvalidPathException("Colon ':' (drive letters or ADS) is forbidden in VFS path: " + value);
            }
        }
    }

    private static VfsPath buildFromValidatedSegments(String[] parts) {
        List<String> validated = new ArrayList<>(parts.length);
        for (String part : parts) {
            validateSegment(part);
            validated.add(part);
        }
        return new VfsPath(List.copyOf(validated), String.join("/", validated));
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public List<String> segments() {
        return segments;
    }

    public int segmentCount() {
        return segments.size();
    }

    public @Nullable String fileName() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    public @Nullable VfsPath parent() {
        if (segments.isEmpty()) {
            return null;
        }
        if (segments.size() == 1) {
            return ROOT;
        }
        List<String> parentSegs = segments.subList(0, segments.size() - 1);
        return new VfsPath(List.copyOf(parentSegs), String.join("/", parentSegs));
    }

    public VfsPath resolve(String child) {
        VfsPath parsedChild = VfsPath.of(child);
        return resolve(parsedChild);
    }

    public VfsPath resolve(VfsPath other) {
        if (other == null) {
            throw new VfsInvalidPathException("Child path must not be null");
        }
        if (other.isRoot()) {
            return this;
        }
        if (this.isRoot()) {
            return other;
        }
        List<String> combined = new ArrayList<>(this.segments.size() + other.segments.size());
        combined.addAll(this.segments);
        combined.addAll(other.segments);
        return new VfsPath(List.copyOf(combined), this.normalized + "/" + other.normalized);
    }

    public VfsPath relativize(VfsPath other) {
        if (other == null || !other.startsWith(this)) {
            throw new VfsInvalidPathException("Cannot relativize '" + other + "' against '" + this + "'");
        }
        if (other.segments.size() == this.segments.size()) {
            return ROOT;
        }
        List<String> sub = other.segments.subList(this.segments.size(), other.segments.size());
        return new VfsPath(List.copyOf(sub), String.join("/", sub));
    }

    public boolean startsWith(VfsPath prefix) {
        if (prefix == null) {
            return false;
        }
        if (prefix.isRoot()) {
            return true;
        }
        if (this.segments.size() < prefix.segments.size()) {
            return false;
        }
        return this.segments.subList(0, prefix.segments.size()).equals(prefix.segments);
    }

    public String asString() {
        return normalized;
    }

    @Override
    public int compareTo(VfsPath other) {
        return this.normalized.compareTo(other.normalized);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof VfsPath other && Objects.equals(normalized, other.normalized));
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }

    @Override
    public String toString() {
        return normalized.isEmpty() ? "/" : normalized;
    }
}
