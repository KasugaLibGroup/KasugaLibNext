package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VfsPath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public interface ScopedPackResources {
    public static Path safeResolve(Path first, String... paths) {
        if (first == null) {
            throw new IllegalArgumentException("Base path must not be null");
        }
        Path normalizedBase = first.toAbsolutePath().normalize();
        Path realBase = Files.exists(normalizedBase) ? resolveRealQuietly(normalizedBase) : null;
        Path iter = normalizedBase;
        for (String raw : paths) {
            if (raw == null) {
                throw new IllegalArgumentException("Path segment must not be null");
            }
            if (raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0) {
                throw new IllegalArgumentException("Path contains forbidden characters: " + raw);
            }
            String trimmed = firstSplash(raw);
            if (!trimmed.isEmpty()) {
                // Legacy workspace globs commonly start with ./; remove only identity segments.
                // Keep .. intact so VfsPath still rejects traversal, even when it could normalize.
                String compatible = java.util.Arrays.stream(trimmed.split("/", -1))
                        .filter(segment -> !segment.equals("."))
                        .collect(java.util.stream.Collectors.joining("/"));
                VfsPath logical = VfsPath.of(compatible).requirePortable();
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
