/**
 * The reload-domain extension point of the unified data-driven framework.
 *
 * <p>{@link lib.kasuga.registration.data_driven.reload.ReloadHandler} is the symmetric counterpart of
 * {@code TypeHandler} on the registration side: it owns one reload content type (e.g. state machine
 * definitions, animation clips) so the reload orchestrator can route files, clear buckets and validate
 * cross-type references through a registry rather than hard-coded type names.
 *
 * <p>{@link lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry} is that registry, ordered
 * by registration because the order drives dispatch and last-wins resolution.
 */
package lib.kasuga.registration.data_driven.reload;
