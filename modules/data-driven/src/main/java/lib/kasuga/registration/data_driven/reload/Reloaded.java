package lib.kasuga.registration.data_driven.reload;

/**
 * A payload that survived duplicate resolution and was handed to a {@link ReloadHandler#register},
 * together with the namespace it came from. The orchestrator carries it through the post-registration
 * phase so a handler's {@link ReloadHandler#afterReload(java.util.List)} can attribute a diagnostic to
 * the mod whose file produced the entry — a generic handler cannot recover that namespace from the
 * payload alone.
 *
 * @param modId   the namespace the entry's file lived in
 * @param payload the registered payload
 * @param <T>     the payload type of the owning handler
 */
public record Reloaded<T>(String modId, T payload) {}
