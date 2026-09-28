package lib.kasuga.registration.data_driven;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.context.BuildContext;

import java.util.List;

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

    default String getParentTypeName() { return null; }

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
}
