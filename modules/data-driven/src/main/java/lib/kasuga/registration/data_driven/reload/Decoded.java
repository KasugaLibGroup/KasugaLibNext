package lib.kasuga.registration.data_driven.reload;

import java.util.List;

/**
 * The outcome of decoding one reload-domain content file with a {@link ReloadHandler}: the entries
 * that decoded plus any human-readable diagnostics.
 *
 * <p>Each {@link Entry} pairs the decoded payload with the identity it will be deduplicated under.
 * The identity is the payload's own id (both reload domains today carry a required {@code id}); the
 * orchestrator never has to know how a handler spells it, so a future payload without a natural id
 * can still participate by choosing one here.
 *
 * @param entries the decoded entries, in file order; empty when the whole file was rejected
 * @param errors  human-readable diagnostics for a rejected file or a skipped entry; empty when the
 *                file decoded cleanly
 * @param <T>     the payload type of the handler that produced this result
 */
public record Decoded<T>(List<Entry<T>> entries, List<String> errors) {

    /**
     * One decoded entry: the payload plus the identity it competes for.
     *
     * @param id      the effective id within the handler's type (its collision space)
     * @param payload the decoded value handed back to {@link ReloadHandler#register(Object)}
     * @param <T>     the payload type
     */
    public record Entry<T>(String id, T payload) {}

    /**
     * An empty decode result: nothing decoded and nothing reported.
     *
     * @param <T> the payload type of the handler that produced this result
     * @return an empty decode result
     */
    public static <T> Decoded<T> empty() {
        return new Decoded<>(List.of(), List.of());
    }
}
