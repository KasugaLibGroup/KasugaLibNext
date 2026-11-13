package lib.kasuga.core.resource.source;

/** Actual bytes read, including derived resources, consume these limits. */
public record InputPolicy(long maxJsonBytes, long maxFileBytes, long maxTotalBytes, int maxFiles,
                          int maxJsonDepth, long memoryThresholdBytes) {
    public static final InputPolicy DEFAULT = new InputPolicy(8L << 20, 256L << 20, 1L << 30, 50_000, 64, 1L << 20);
    public InputPolicy {
        if (maxJsonBytes < 1 || maxFileBytes < maxJsonBytes || maxTotalBytes < maxFileBytes
                || maxFiles < 1 || maxJsonDepth < 1 || maxJsonDepth > 256 || memoryThresholdBytes < 0 || memoryThresholdBytes > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid input budgets");
    }
}
