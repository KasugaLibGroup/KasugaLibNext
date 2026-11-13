package lib.kasuga.core.vfs;

import java.util.Optional;

/**
 * Metadata snapshot for a node inside a {@link VirtualFileSystem}.
 */
public record VfsNodeStat(
        VfsPath path,
        VfsNodeKind kind,
        long size,
        long lastModifiedMillis,
        Optional<String> sha256
) {
    public static VfsNodeStat missing(VfsPath path) {
        return new VfsNodeStat(path, VfsNodeKind.MISSING, 0L, 0L, Optional.empty());
    }

    public static VfsNodeStat directory(VfsPath path, long lastModifiedMillis) {
        return new VfsNodeStat(path, VfsNodeKind.DIRECTORY, 0L, lastModifiedMillis, Optional.empty());
    }

    public static VfsNodeStat file(VfsPath path, long size, long lastModifiedMillis, Optional<String> sha256) {
        return new VfsNodeStat(path, VfsNodeKind.FILE, size, lastModifiedMillis, sha256);
    }

    public boolean exists() {
        return kind != VfsNodeKind.MISSING;
    }

    public boolean isFile() {
        return kind == VfsNodeKind.FILE;
    }

    public boolean isDirectory() {
        return kind == VfsNodeKind.DIRECTORY;
    }
}
