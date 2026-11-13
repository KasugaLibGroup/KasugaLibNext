package lib.kasuga.core.vfs.backend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared internal helpers for VFS backends (SHA-256 fingerprinting, atomic buffered write streams, and constants).
 */
final class VfsSupport {
    static final String TEMP_FILE_PREFIX = ".ksgvfs-";

    private VfsSupport() {
    }

    @FunctionalInterface
    interface BytesCommitter {
        void commit(byte[] bytes) throws IOException;
    }

    static OutputStream bufferedAtomicOutputStream(BytesCommitter committer) {
        return new ByteArrayOutputStream() {
            private boolean streamClosed = false;

            @Override
            public void close() throws IOException {
                if (!streamClosed) {
                    streamClosed = true;
                    committer.commit(toByteArray());
                }
            }
        };
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    static String hash(InputStream input, MessageDigest digest, byte[] buffer) throws IOException {
        digest.reset();
        for (int count; (count = input.read(buffer)) != -1;) if (count > 0) digest.update(buffer, 0, count);
        return HexFormat.of().formatHex(digest.digest());
    }
}
