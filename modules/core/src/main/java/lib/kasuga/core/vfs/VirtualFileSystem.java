package lib.kasuga.core.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Cross-platform writable Virtual File System abstraction supporting local directories,
 * in-memory mounts, read-only archives, composite overlays, atomic transactions, and
 * dual-origin update hooks.
 */
public interface VirtualFileSystem extends AutoCloseable {
    String sourceId();

    boolean isReadOnly();

    boolean exists(VfsPath path);

    default boolean isRegularFile(VfsPath path) {
        try {
            return stat(path).isFile();
        } catch (IOException e) {
            return false;
        }
    }

    default boolean isDirectory(VfsPath path) {
        try {
            return stat(path).isDirectory();
        } catch (IOException e) {
            return false;
        }
    }

    VfsNodeStat stat(VfsPath path) throws IOException;

    InputStream openRead(VfsPath path) throws IOException;

    default byte[] readBytes(VfsPath path) throws IOException {
        try (InputStream in = openRead(path)) {
            return in.readAllBytes();
        }
    }

    default String readString(VfsPath path) throws IOException {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    List<String> list(VfsPath dir) throws IOException;

    List<VfsPath> walk(VfsPath prefix) throws IOException;

    void writeBytes(VfsPath path, byte[] content) throws IOException;

    default void writeString(VfsPath path, String content) throws IOException {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    OutputStream openWrite(VfsPath path) throws IOException;

    void mkdirs(VfsPath dir) throws IOException;

    boolean delete(VfsPath path) throws IOException;

    void move(VfsPath from, VfsPath to) throws IOException;

    VfsWriteTransaction beginTransaction() throws IOException;

    VfsSubscription watch(VfsPath prefix, VfsWatchListener listener);

    default VfsSubscription watchAll(VfsWatchListener listener) {
        return watch(VfsPath.ROOT, listener);
    }

    Optional<VfsChangeEvent> pollExternalChanges() throws IOException;

    @Override
    void close() throws IOException;
}
