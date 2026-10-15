package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The definition bucket: {@link StateMachineDefinition}s by {@link Id}, split from the
 * instance bucket ({@link FsmMachines}) on purpose — a resource reload ({@link #clearResource()})
 * rebuilds this bucket without destroying live machines, whose lifecycle is owned by their host.
 *
 * <p>Script definitions win over resource definitions ("script wins"): a {@link #register} call
 * replaces a RESOURCE entry, and a later {@link #registerResource} call does not clobber a SCRIPT
 * entry. Every identity change of an id's entry — overwrite on register, {@link #remove},
 * {@link #clearResource()}, {@link #clearAll()} — notifies {@link InvalidationListener}s with that id.
 *
 * <p>This bucket has zero dependencies: inline state-var cleanup on invalidation is wired by the
 * composition root ({@link FsmRegistries}), which subscribes a listener that clears the owning
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry}.
 */
public final class FsmDefinitions {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where a definition came from — drives reload / overwrite semantics. */
    public enum DefinitionSource {
        /** Loaded from resources by the host's state machine definition loader. */
        RESOURCE,
        /** Registered at runtime (scripting APIs). */
        SCRIPT
    }

    /** A definition plus its provenance and a content hash for sync identity checks. */
    public record DefinitionEntry(StateMachineDefinition definition, DefinitionSource source, int contentHash) {}

    /**
     * Notified with an id whenever that id's definition entry is invalidated: overwritten by a
     * re-registration, removed, or dropped by a clear. Reserved for hosts that rebuild machines on
     * invalidation and for the composition root's inline-var cleanup.
     */
    public interface InvalidationListener {
        void onInvalidated(Id id);
    }

    private final Map<Id, DefinitionEntry> definitionsById = new ConcurrentHashMap<>();
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a runtime (scripting) definition, idempotent overwrite. Script definitions win over
     * resource definitions: if the id is already taken by a RESOURCE entry it is replaced, with a
     * warning — script wins.
     */
    public void register(Id id, StateMachineDefinition definition) {
        DefinitionEntry previous = definitionsById.put(id, new DefinitionEntry(definition, DefinitionSource.SCRIPT, hashOf(definition)));
        if (previous != null && previous.source() == DefinitionSource.RESOURCE) {
            LOGGER.warn("Script definition '{}' overwrote a resource definition; script wins", id);
        }
        if (previous != null) {
            notifyInvalidated(id);
        }
    }

    /** Register a definition loaded from a resource pack. Does not clobber a SCRIPT definition of
     * the same id (script wins); later RESOURCE writes overwrite earlier ones. */
    public void registerResource(Id id, StateMachineDefinition definition) {
        definitionsById.compute(id, (k, existing) -> {
            if (existing != null && existing.source() == DefinitionSource.SCRIPT) {
                return existing;
            }
            if (existing != null) {
                notifyInvalidated(id);
            }
            return new DefinitionEntry(definition, DefinitionSource.RESOURCE, hashOf(definition));
        });
    }

    /** Drop every resource-pack definition (reload path). Script definitions survive. */
    public void clearResource() {
        definitionsById.entrySet().removeIf(entry -> {
            if (entry.getValue().source() == DefinitionSource.RESOURCE) {
                notifyInvalidated(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /** Drop every definition, regardless of source. */
    public void clearAll() {
        for (Id id : definitionsById.keySet()) {
            notifyInvalidated(id);
        }
        definitionsById.clear();
    }

    /**
     * Remove a single definition (SCRIPT or RESOURCE) by id. Lets a script definition be removed so
     * a resource reload (which re-registers RESOURCE) can restore the resource version. Notifies
     * {@link InvalidationListener}s. Returns true if the id was present.
     */
    public boolean remove(Id id) {
        DefinitionEntry previous = definitionsById.remove(id);
        if (previous == null) {
            return false;
        }
        notifyInvalidated(id);
        return true;
    }

    public StateMachineDefinition get(Id id) {
        DefinitionEntry entry = definitionsById.get(id);
        return entry == null ? null : entry.definition();
    }

    /**
     * Content hash of the definition registered under {@code id} (0 if absent). Used by the FSM sync layer
     * as the per-definition identity check: it is independent per id, so re-registering an unrelated
     * definition does not invalidate other machines' sync.
     */
    public int hash(Id id) {
        DefinitionEntry entry = definitionsById.get(id);
        return entry == null ? 0 : entry.contentHash();
    }

    /** Stable 32-bit hash of a definition's codec-encoded form (deterministic for equal definitions). */
    public static int hashOf(StateMachineDefinition definition) {
        if (definition == null) {
            return 0;
        }
        return StateMachineDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition)
                .result()
                .map(json -> json.toString().hashCode())
                .orElse(0);
    }

    public void addListener(InvalidationListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyInvalidated(Id id) {
        for (InvalidationListener listener : listeners) {
            listener.onInvalidated(id);
        }
    }
}
