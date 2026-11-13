package lib.kasuga.core.vfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Staging transaction allowing multiple VFS mutations to be validated and committed atomically
 * with a single coherent {@link VfsChangeEvent}.
 */
public interface VfsWriteTransaction extends AutoCloseable {
    VfsWriteTransaction putBytes(VfsPath path, byte[] content);

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
