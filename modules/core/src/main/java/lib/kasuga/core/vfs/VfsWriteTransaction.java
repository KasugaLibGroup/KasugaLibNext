package lib.kasuga.core.vfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;

/**
 * Staging transaction allowing multiple VFS mutations to be validated and committed atomically
 * with a single coherent {@link VfsChangeEvent}.
 */
public interface VfsWriteTransaction extends AutoCloseable {
    @FunctionalInterface
    interface ContentSource {
        /** Returns a newly owned stream; the transaction closes it during preparation. */
        InputStream open() throws IOException;
    }
    VfsWriteTransaction putBytes(VfsPath path, byte[] content);

    /** Source must stay readable until preparation; all sources are staged before any apply. */
    default VfsWriteTransaction putStream(VfsPath path, ContentSource source) throws IOException {
        try (InputStream input = source.open()) { return putBytes(path, input.readAllBytes()); }
    }

    /** Stable reservation identity shared by transactions on the same backend. */
    default Object coordinationKey() { return this; }

    /** Materializes deferred inputs without holding backend reservations. Safe to call repeatedly. */
    default void stage() throws IOException {}

    default VfsWriteTransaction putString(VfsPath path, String content) {
        return putBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    VfsWriteTransaction mkdirs(VfsPath dir);

    VfsWriteTransaction delete(VfsPath path);

    VfsWriteTransaction move(VfsPath from, VfsPath to);

    VfsChangeEvent commit() throws IOException;

    /**
     * Optional reversible preparation for coordinated commits. Implementations without this
     * capability are rejected by multi-backend coordinators before any participant is applied.
     */
    default VfsPreparedTransaction prepare() throws IOException {
        throw new VfsException("This backend does not support coordinated transactions");
    }

    void rollback();

    @Override
    void close();
}
