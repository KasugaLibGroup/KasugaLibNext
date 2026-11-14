package lib.kasuga.registration.data_driven;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.HandlerCapability;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaim;
import lib.kasuga.registration.data_driven.validation.ValidationContext;

import java.util.List;
import java.util.Set;

public interface TypeHandler<T> {

    /** Construction-phase handler applied first: groups must exist before content attaches to them. */
    int PHASE_GROUPS = 0;

    /** Construction-phase handler applied after groups: blocks and items. */
    int PHASE_CONTENT = 1;

    /** Construction-phase handler applied after its parent blocks: embedded entities (block entities). */
    int PHASE_EMBEDDED = 2;

    /** Top-level JSON field this handler consumes, e.g. {@code "blocks"} or {@code "items"}. */
    String getTypeName();

    /**
     * Lifecycle phase this handler is applied in. Handlers with a lower value are applied first
     * within their phase; groups must be applied before blocks/items so that blocks can attach to
     * already-created groups, and embedded entities after their parent blocks.
     */
    int getPhase();

    T parse(JsonObject json);

    void apply(T definition, BuildContext context);

    /**
     * Capabilities declared to strict multi-source preparation. Existing handlers retain their
     * historical behavior by declaring none; strict callers reject those handlers before apply.
     */
    default Set<HandlerCapability> capabilities() {
        return Set.of();
    }

    /**
     * Performs side-effect-free validation against the complete prepared definition directory.
     * Handlers that support strict preparation must implement this method.
     */
    default void validate(T definition, ValidationContext context) {
    }

    /**
     * Declares every final Minecraft registry entry produced by this definition.
     * The return value is empty for configuration handlers and may be empty for a registration
     * handler whose factory creates no direct registry entry in this session.
     */
    default List<RegistryClaim> claims(T definition, ValidationContext context) {
        return List.of();
    }

    /**
     * Returns the definition that must be applied before this one. This is used for stable parent
     * ordering and cycle detection inside a handler phase.
     */
    default DefinitionKey parentDefinition(T definition) {
        return null;
    }

    /**
     * Top-level JSON field whose entries carry this handler's data as a nested object. An embedded
     * type never occupies a top-level field of its own; it only ever arrives through
     * {@link #extractEmbedded(JsonObject)}. {@code null} means "this is a top-level type".
     */
    default String getParentTypeName() { return null; }

    /**
     * Key inside a parent entry's object that holds this embedded type's definition, e.g.
     * {@code block_entity} inside a {@code blocks} entry. Only meaningful when
     * {@link #getParentTypeName()} is non-null; diagnostics name it so an author who wrote the type
     * as a top-level field is told exactly where it belongs.
     *
     * @return the embedded key, or {@code null} when this handler is not embedded
     */
    default String getEmbeddedKeyName() { return null; }

    default List<JsonObject> extractEmbedded(JsonObject parentJson) { return null; }

    /**
     * Stable identity of one parsed definition within its own type field, used by the loader to
     * resolve duplicate ids before any registration side effect happens. Two definitions of the same
     * handler that return equal, non-{@code null} identities are duplicates.
     *
     * <p>The default returns {@code null}, meaning "no identity, never deduplicated", so custom
     * handlers (e.g. a generic effect type with no registry id) keep working unchanged.
     *
     * @param modId      the owning mod's id, for ids that carry no namespace of their own
     * @param definition the parsed definition
     * @return the normalized identity, or {@code null} to opt out of duplicate detection
     */
    default String resolveIdentity(String modId, T definition) { return null; }

    /**
     * Policy-aware identity used by the strict multi-source pipeline. Legacy handlers keep their
     * previous behavior unless they opt into the distinction explicitly.
     */
    default String resolveIdentity(String modId, T definition, boolean strict) {
        return resolveIdentity(modId, definition);
    }
}
