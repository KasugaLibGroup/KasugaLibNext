package lib.kasuga.rendering.models.uml.dynamic.fsm.state;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link StateVar}s by {@link Id} — the FSM's typed-state analog of
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary}. Java code, JSON definitions
 * and scripting engines resolve vars by id; the data-driven factory registers anonymous vars declared inline.
 *
 * <p>Inline vars are registered with an <b>owner</b> ({@link #registerOwned}) — the machine definition id
 * they were declared by — so {@link #clearForMachine} can drop exactly that machine's vars instead of
 * guessing by id prefix. Shared instances live on the composition root
 * ({@link lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries}).
 */
public final class StateVarRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<Id, StateVar<?>> vars = new ConcurrentHashMap<>();
    /** machineId → the inline var ids that machine's definition registered via {@link #registerOwned}. */
    private final Map<Id, Set<Id>> ownedByMachine = new ConcurrentHashMap<>();

    public StateVarRegistry() {}

    /**
     * Register {@code var} under its id (idempotent overwrite), without an owner. Logs a warning if an
     * existing entry under the same id had a different type or default.
     */
    public <T> StateVar<T> register(StateVar<T> var) {
        StateVar<?> previous = vars.put(var.id(), var);
        if (previous != null && !compatible(previous, var)) {
            LOGGER.warn("State var '{}' re-registered with a different type/default; new registration wins", var.id());
        }
        return var;
    }

    /**
     * Register {@code var} under its id and record {@code machineId} as its owner, so a later
     * {@link #clearForMachine} drops exactly this var. Used by the data-driven factory for vars
     * declared inline in a machine definition.
     */
    public <T> StateVar<T> registerOwned(StateVar<T> var, Id machineId) {
        StateVar<T> registered = register(var);
        if (machineId != null) {
            ownedByMachine.computeIfAbsent(machineId, k -> ConcurrentHashMap.newKeySet()).add(var.id());
        }
        return registered;
    }

    /** True iff {@code a} and {@code b} share type, default value and ephemeral flag (used for re-register checks). */
    public static boolean compatible(StateVar<?> a, StateVar<?> b) {
        return a.type().equals(b.type())
                && a.defaultValue().equals(b.defaultValue())
                && a.ephemeral() == b.ephemeral();
    }

    public StateVar<?> get(Id id) {
        return vars.get(id);
    }

    public boolean has(Id id) {
        return vars.containsKey(id);
    }

    public Set<Id> ids() {
        return vars.keySet();
    }

    /** Resolve a {@code "namespace:path"} string; {@code null} if unparseable or unregistered. */
    public StateVar<?> resolve(String id) {
        Id loc = Id.tryParse(id);
        return loc == null ? null : vars.get(loc);
    }

    public void clear() {
        vars.clear();
        ownedByMachine.clear();
    }

    /**
     * Remove every inline var owned by {@code machineId} (registered via {@link #registerOwned}).
     * Called when a machine definition is cleared or removed so reloads don't leak inline vars.
     *
     * <p>Ownership is exact: clearing {@code test:a} never touches {@code test:a/b}'s vars. Vars
     * registered without an owner (legacy data predating ownership tracking) are not covered by the
     * ownership table; for those this falls back to the historical prefix match
     * ({@code <machineId.path>/}), which can over-match sibling paths.
     */
    public void clearForMachine(Id machineId) {
        if (machineId == null) {
            return;
        }
        Set<Id> owned = ownedByMachine.remove(machineId);
        if (owned != null) {
            for (Id varId : owned) {
                vars.remove(varId);
            }
            return;
        }
        // fallback for vars registered without an owner: prefix match (may over-match siblings)
        String namespace = machineId.getNamespace();
        String pathPrefix = machineId.getPath() + "/";
        vars.keySet().removeIf(id -> id.getNamespace().equals(namespace) && id.getPath().startsWith(pathPrefix));
    }
}
