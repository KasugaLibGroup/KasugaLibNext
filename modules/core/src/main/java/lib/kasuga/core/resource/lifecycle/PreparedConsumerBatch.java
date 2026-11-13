package lib.kasuga.core.resource.lifecycle;

/** Publication objects are reached through a single ResourceGeneration. MC side effects are not rollbackable. */
public interface PreparedConsumerBatch extends AutoCloseable {
    Object value();
    default void publish() throws Exception {}
    /** Revoke new calls immediately, even while old readers still retain leases. */
    default void retire() {}
    @Override void close() throws Exception;
}
