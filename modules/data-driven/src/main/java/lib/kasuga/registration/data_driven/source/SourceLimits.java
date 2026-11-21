package lib.kasuga.registration.data_driven.source;

/**
 * Technical limits applied to one source.
 */
public record SourceLimits(
        long maxJsonFileBytes,
        long maxFileBytes,
        long maxTotalBytes,
        int maxFiles
) {
    public static final SourceLimits DEFAULT = new SourceLimits(
            8L * 1024 * 1024,
            256L * 1024 * 1024,
            1024L * 1024 * 1024,
            50_000
    );

    public SourceLimits {
        if (maxJsonFileBytes <= 0 || maxFileBytes <= 0 || maxTotalBytes <= 0 || maxFiles <= 0) {
            throw new IllegalArgumentException("source limits must all be positive");
        }
        if (maxJsonFileBytes > maxFileBytes) {
            throw new IllegalArgumentException("maxJsonFileBytes must not exceed maxFileBytes");
        }
        if (maxFileBytes > maxTotalBytes) {
            throw new IllegalArgumentException("maxFileBytes must not exceed maxTotalBytes");
        }
    }

    public long limitFor(LogicalPath path) {
        return path.value().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? maxJsonFileBytes
                : maxFileBytes;
    }
}
