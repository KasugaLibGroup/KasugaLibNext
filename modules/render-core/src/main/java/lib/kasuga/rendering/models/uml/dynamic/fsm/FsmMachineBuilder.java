package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Convenience façade for building host machines (block entities / entities) from data-driven
 * definitions. Delegates to {@link DefinitionStateMachineFactory}; a non-null {@link PoseSink}
 * marks the machine client-side (factory contract).
 *
 * <p>Host machines are <b>not</b> registered into {@link FsmMachines} — instance registration
 * is exclusive to the scripting API; definitions are read from the shared definition bucket
 * ({@link FsmRegistries#GLOBAL}, populated by the resource loader): the definition is shared,
 * but each host gets its own machine structure.
 */
public final class FsmMachineBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FsmMachineBuilder() {}

    /**
     * Build a machine for the given owner from a resolved definition.
     *
     * @return the machine, or {@code null} (with a warning) when the definition is missing or the
     *         build fails — callers must treat null as "no animation".
     */
    @Nullable
    public static StateMachine<Object> build(Object owner, @Nullable StateMachineDefinition definition, @Nullable PoseSink sink) {
        if (definition == null) {
            LOGGER.warn("[FsmMachineBuilder] cannot build: definition is null (owner={})", owner);
            return null;
        }
        try {
            return new DefinitionStateMachineFactory<Object>(FsmRegistries.GLOBAL.functions()).build(owner, definition, sink);
        } catch (Exception e) {
            LOGGER.warn("[FsmMachineBuilder] failed to build machine '{}' for {}: {}", definition.id(), owner, e.getMessage());
            LOGGER.debug("Full stacktrace:", e);
            return null;
        }
    }

    /** Resolve a definition from the shared definition bucket ({@link FsmRegistries#GLOBAL}). */
    @Nullable
    public static StateMachineDefinition findDefinition(Id id) {
        if (id == null) {
            return null;
        }
        return FsmRegistries.GLOBAL.definitions().get(id);
    }
}
