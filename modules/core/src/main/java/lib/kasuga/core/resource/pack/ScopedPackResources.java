package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VfsInvalidPathException;
import lib.kasuga.core.vfs.VfsPath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public interface ScopedPackResources {
    /**
     * Normalizes a legacy resource/workspace path into a {@link VfsPath}, stripping only
     * identity {@code .} segments while rejecting traversal ({@code ..}), empty internal
     * segments, backslashes, colons, and control characters.
     */
    public static VfsPath toLogicalPath(String raw) {
        if (raw == null) {
            throw new VfsInvalidPathException("Path segment must not be null");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\' || c == ':') {
                throw new VfsInvalidPathException("Path contains forbidden characters: " + raw);
            }
        }
        int start = 0;
        int end = raw.length();
        while (start < end && raw.charAt(start) == '/') {
            start++;
        }
        while (end > start && raw.charAt(end - 1) == '/') {
            end--;
        }
        if (start >= end) {
            return VfsPath.ROOT;
        }
        String[] parts = raw.substring(start, end).split("/", -1);
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new VfsInvalidPathException("VFS path segment must not be empty: " + raw);
            }
            if (".".equals(part)) {
                continue;
            }
            VfsPath.validateSegment(part);
            segments.add(part);
        }
        return VfsPath.ofSegments(segments);
    }

    public static VfsPath toLogicalPath(String prefix, String path) {
        VfsPath prefixPath = (prefix == null || prefix.isEmpty()) ? VfsPath.ROOT : toLogicalPath(prefix);
        if (path == null || path.isEmpty()) {
            return prefixPath;
        }
        VfsPath subPath = toLogicalPath(path);
        return subPath.isRoot() ? prefixPath : prefixPath.resolve(subPath);
    }

    public static Path safeResolve(Path first, String... paths) {
        if (first == null) {
            throw new IllegalArgumentException("Base path must not be null");
        }
        Path normalizedBase = first.toAbsolutePath().normalize();
        Path realBase = Files.exists(normalizedBase) ? resolveRealQuietly(normalizedBase) : null;
        Path iter = normalizedBase;
        for (String raw : paths) {
            VfsPath logical = toLogicalPath(raw).requirePortable();
            for (String seg : logical.segments()) {
                iter = iter.resolve(seg);
                if (realBase != null && Files.exists(iter, LinkOption.NOFOLLOW_LINKS)) {
                    Path realIter = resolveRealQuietly(iter);
                    if (realIter == null || !realIter.startsWith(realBase)) {
                        throw new IllegalArgumentException("Path is not safe (escapes base via symlink): " + raw);
                    }
                }
            }
        }
        Path normalizedResolved = iter.normalize();
        if (!normalizedResolved.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Path is not safe (relative to the first path)");
        }
        return normalizedResolved;
    }

    private static Path resolveRealQuietly(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
    public static String firstSplash(String path){
        while(path.startsWith("/"))
            path = path.substring(1);
        return path;
    }
    public InputStream open(String prefix, String path) throws IOException;
    public boolean exists(String prefix, String path);
    public boolean isRegularFile(String prefix, String path);
    public boolean isDirectory(String prefix, String path);

    String getName();
}
