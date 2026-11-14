package lib.kasuga.registration.data_driven.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Enforces a byte limit from bytes actually read from the wrapped stream.
 */
final class LimitedInputStream extends FilterInputStream {
    private final SourceDescriptor source;
    private final LogicalPath path;
    private final long limit;
    private long count;

    LimitedInputStream(InputStream input, SourceDescriptor source, LogicalPath path, long limit) {
        super(input);
        this.source = source;
        this.path = path;
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count++;
            if (count > limit) {
                throw exceeded();
            }
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long remainingIncludingTerminator = Math.max(0, limit - count) + 1;
        int requested = (int) Math.min(length, remainingIncludingTerminator);
        int read = super.read(buffer, offset, requested);
        if (read < 0) {
            return -1;
        }
        count += read;
        if (count > limit) {
            throw exceeded();
        }
        return read;
    }

    @Override
    public long skip(long amount) throws IOException {
        long read = super.skip(Math.min(amount, Math.max(0, limit - count) + 1));
        count += read;
        if (count > limit) {
            throw exceeded();
        }
        return read;
    }

    private SourceAccessException exceeded() {
        return new SourceAccessException(
                SourceAccessException.Reason.LIMIT_EXCEEDED,
                source,
                path,
                "file exceeds the " + limit + " byte limit: " + path
        );
    }
}
