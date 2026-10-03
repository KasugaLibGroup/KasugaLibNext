package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmAction;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmCondition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarType;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Builds a runtime, owner-generic {@link StateMachine} from a data-driven
 * {@link StateMachineDefinition}. Generic over {@code <O>} so a Java host gets a
 * {@code StateMachine<MyActor>} (typed {@code ctx.owner()}, no cast). Behavior references are resolved
 * through the provided {@link FsmFunctionLibrary}; inline pose data is applied directly. Declared
 * {@link StateVarDefinition state vars} are resolved (or anonymously registered) through the
 * {@link StateVarRegistry}.
 *
 * <p>References missing from the library / registry, or structural typos (unknown {@code initial_state} /
 * {@code from} / {@code to}), are <em>logged, not silent</em>: {@link #validateDefinition} aggregates them
 * into one warning, and the build degrades (condition → false, action → no-op, unresolved transition → skipped,
 * unresolved trigger → the transition drops its {@code .on(...)}).
 *
 * @param <O> the owner type threaded through the built machine
 */
public final class DefinitionStateMachineFactory<O> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final FsmFunctionLibrary library;
    private final StateVarRegistry stateVars;
    private final FsmAnimationClips clips;

    public DefinitionStateMachineFactory(FsmFunctionLibrary library) {
        this(library, FsmRegistries.GLOBAL.vars(), FsmRegistries.GLOBAL.clips());
    }

    public DefinitionStateMachineFactory(FsmFunctionLibrary library, StateVarRegistry stateVars) {
        this(library, stateVars, FsmRegistries.GLOBAL.clips());
    }

    public DefinitionStateMachineFactory(FsmFunctionLibrary library, StateVarRegistry stateVars, FsmAnimationClips clips) {
        this.library = library;
        this.stateVars = stateVars;
        this.clips = clips;
    }

    /**
     * Build a {@link StateMachine} for the given owner using the supplied definition.
     */
    public StateMachine<O> build(O owner, StateMachineDefinition definition) {
        return build(owner, definition, null);
    }

    /**
     * Build and configure the full machine. This is the canonical entry point.
     */
    public StateMachine<O> build(O owner, StateMachineDefinition definition, PoseSink sink) {
        validateDefinition(definition);
        Map<String, StateVar<?>> varsByName = resolveStateVars(definition);
        StateMachine.Builder<O> builder = StateMachine.<O>builder(owner)
                .clientSide(sink != null)
                .declaredVars(new HashSet<>(varsByName.values()));
        if (sink != null) {
            builder.sink(sink);
        }
        for (LayerDefinition layerDef : definition.layers()) {
            builder.layer(buildLayer(layerDef, varsByName, definition.id()));
        }
        return builder.build();
    }

    //region state vars

    /**
     * Resolve every declared state var to a registered {@link StateVar} (by reference) or an anonymously
     * registered one (inline declarations). Returns them keyed by declared name so transition
     * {@code trigger_on} strings can resolve against this machine's own vars.
     */
    private Map<String, StateVar<?>> resolveStateVars(StateMachineDefinition definition) {
        Map<String, StateVar<?>> byName = new HashMap<>();
        for (StateVarDefinition def : definition.stateVars()) {
            StateVar<?> var = def.reference().isPresent()
                    ? resolveReference(definition.id(), def.reference().get())
                    : resolveInline(definition.id(), def);
            if (var != null) {
                byName.put(def.name(), var);
            }
        }
        return byName;
    }

    private StateVar<?> resolveReference(Id machineId, String reference) {
        StateVar<?> var = stateVars.resolve(reference);
        if (var == null) {
            LOGGER.warn("State machine '{}' references unknown state var '{}'", machineId, reference);
            return null;
        }
        // keep an already-registered spec's attributes; wrap a plain var with the default attributes
        // (external-writable, non-sync = the pre-parameter-store behavior)
        return var instanceof ParameterSpec<?> ps ? ps : ParameterSpec.of(var);
    }

    @SuppressWarnings("unchecked")
    private <T> StateVar<T> resolveInline(Id machineId, StateVarDefinition def) {
        StateVarType<T> type = (StateVarType<T>) StateVarType.byToken(def.type());
        T defaultValue = def.defaultValue().isPresent()
                ? decodeOrDefault(type.codec(), def.defaultValue().get(), type.zeroDefault(), def.name())
                : type.zeroDefault();
        ParameterSpec.Builder<T> builder = ParameterSpec.<T>parameter(idFor(machineId, def.name()), type.type(), type.codec())
                .defaultValue(defaultValue)
                .externalWritable(def.externalWritable())
                .sync(def.sync());
        if (def.ephemeral()) {
            builder.ephemeral();
        }
        // owned by this machine's definition, so a definition clear/reload drops exactly these vars
        return stateVars.registerOwned(builder.build(), machineId);
    }

    private <T> T decodeOrDefault(Codec<T> codec, JsonElement element, T fallback, String name) {
        return codec.decode(JsonOps.INSTANCE, element)
                .resultOrPartial(err -> LOGGER.warn(
                        "State var '{}' default decode failed ({}); using fallback", name, err))
                .map(Pair::getFirst)
                .orElse(fallback);
    }

    private static Id idFor(Id machineId, String name) {
        return Id.fromNamespaceAndPath(machineId.getNamespace(), machineId.getPath() + "/" + name);
    }

    //endregion

    private Layer<O> buildLayer(LayerDefinition layerDef, Map<String, StateVar<?>> varsByName, Id machineId) {
        Layer<O> layer = new Layer<>(layerDef.id());
        layer.weight(layerDef.weight());
        switch (layerDef.mode()) {
            case ADDITIVE -> layer.additive();
            case OVERRIDE -> layer.override();
            default -> layer.base();
        }
        layer.boneMask(layerDef.resolvedMask());

        Map<String, State<O>> stateById = new HashMap<>();
        for (StateDefinition stateDef : layerDef.states()) {
            State<O> state = layer.state(stateDef.id(), s -> configureState(s, stateDef, machineId));
            stateById.put(stateDef.id(), state);
        }

        layer.initial(stateById.get(layerDef.initialState()));

        for (TransitionDefinition transDef : layerDef.transitions()) {
            State<O> from = stateById.get(transDef.from());
            State<O> to = stateById.get(transDef.to());
            if (from == null || to == null) {
                continue;
            }
            Transition<O> transition = layer.transition(transDef.id(), from, to);
            configureTransition(transition, transDef, varsByName, machineId);
        }
        return layer;
    }

    private void configureState(State<O> state, StateDefinition def, Id machineId) {
        def.durationTicks().ifPresent(state::durationTicks);
        applyPose(state, def.pose());
        def.clip().ifPresent(clipDef -> {
            FsmAnimationClips.Entry entry = clips.get(clipDef.id());
            if (entry != null) {
                state.clip(entry.sampler(), entry.data(), clipDef.loop());
            } else {
                LOGGER.warn("State machine '{}' state '{}' references unknown clip '{}'; degrading to static pose",
                        machineId, def.id(), clipDef.id());
            }
        });
        for (Id id : def.onEnter()) {
            state.onEnter(action(id));
        }
        for (Id id : def.onExit()) {
            state.onExit(action(id));
        }
        for (Id id : def.onUpdate()) {
            state.onUpdate(action(id));
        }
    }

    private void configureTransition(
            Transition<O> transition,
            TransitionDefinition def,
            Map<String, StateVar<?>> varsByName,
            Id machineId
    ) {
        transition.crossFade(def.crossFadeSeconds());
        if (def.whenComplete()) {
            transition.whenComplete();
        }
        def.triggerOn().ifPresent(triggerId -> {
            StateVar<Boolean> trigger = resolveTrigger(triggerId, varsByName, machineId);
            if (trigger != null) {
                transition.on(trigger);
            }
        });
        for (Id id : def.when()) {
            transition.when(condition(id));
        }
        for (Id id : def.onFire()) {
            transition.onFire(action(id));
        }
    }

    private StateVar<Boolean> resolveTrigger(String id, Map<String, StateVar<?>> varsByName, Id machineId) {
        StateVar<?> var = varsByName.get(id);
        if (var == null) {
            var = stateVars.resolve(id);
        }
        if (var == null) {
            LOGGER.warn("State machine '{}' transition references unknown trigger var '{}'", machineId, id);
            return null;
        }
        if (var.type() != Boolean.class) {
            LOGGER.warn("State machine '{}' trigger var '{}' is not boolean (got {})", machineId, id, var.type());
            return null;
        }
        if (!var.ephemeral()) {
            LOGGER.warn("State machine '{}' trigger var '{}' is not ephemeral; using tick-scoped semantics (a persistent bool trigger fires every tick until cleared)", machineId, id);
        }
        return (StateVar<Boolean>) var;
    }

    private void applyPose(State<O> state, PoseDefinition def) {
        for (Map.Entry<String, Float> morph : def.morphs().entrySet()) {
            state.morph(morph.getKey(), morph.getValue());
        }

        for (PoseDefinition.BoneDefinition boneDef : def.bones()) {
            state.bone(boneDef.name(), buildTransform(boneDef.transform()), ApplyMode.byName(boneDef.mode()));
        }

        for (PoseDefinition.FrameDefinition frameDef : def.frames()) {
            state.frame(frameDef.material(), frameDef.frame());
        }
    }

    private Transform buildTransform(TransformDefinition def) {
        Transform transform = new Transform();
        Vector3f t = def.translate();
        transform.translate(t.x, t.y, t.z);
        Vector3f r = def.rotate();
        transform.rotate(r.x, r.y, r.z, true);
        Vector3f s = def.scale();
        transform.scale(s.x, s.y, s.z);
        return transform;
    }

    @SuppressWarnings("unchecked")
    private Predicate<StateContext<O>> condition(Id id) {
        FsmCondition<?> condition = library.condition(id);
        return condition != null ? ctx -> ((FsmCondition<O>) condition).test(ctx) : ctx -> false;
    }

    @SuppressWarnings("unchecked")
    private Consumer<StateContext<O>> action(Id id) {
        FsmAction<?> action = library.action(id);
        return action != null ? ((FsmAction<O>) action)::accept : ctx -> {};
    }

    /**
     * Aggregate-log every missing or invalid reference — behavior refs (missing library functions) AND
     * structural typos (unknown {@code initial_state} / {@code from} / {@code to}) — as one warning for the
     * whole definition. The build still proceeds: missing conditions degrade to {@code false}, missing
     * actions to no-ops, unresolved transitions are skipped in {@link #buildLayer}.
     */
    void validateDefinition(StateMachineDefinition definition) {
        Set<String> missing = new LinkedHashSet<>();
        for (LayerDefinition layerDef : definition.layers()) {
            Set<String> stateIds = new HashSet<>();
            for (StateDefinition stateDef : layerDef.states()) {
                stateIds.add(stateDef.id());
                stateDef.clip().ifPresent(clipDef -> {
                    if (clips.get(clipDef.id()) == null) {
                        missing.add(layerDef.id() + ": state '" + stateDef.id() + "' references unknown clip '" + clipDef.id() + "'");
                    }
                });
                for (Id id : stateDef.onEnter()) {
                    if (!library.hasAction(id)) missing.add(id.toString());
                }
                for (Id id : stateDef.onExit()) {
                    if (!library.hasAction(id)) missing.add(id.toString());
                }
                for (Id id : stateDef.onUpdate()) {
                    if (!library.hasAction(id)) missing.add(id.toString());
                }
            }
            String initial = layerDef.initialState();
            if (initial != null && !stateIds.contains(initial)) {
                missing.add(layerDef.id() + ": unknown initial_state '" + initial + "'");
            }
            for (TransitionDefinition transDef : layerDef.transitions()) {
                if (!stateIds.contains(transDef.from())) {
                    missing.add(layerDef.id() + ": transition '" + transDef.id() + "' unknown from '" + transDef.from() + "'");
                }
                if (!stateIds.contains(transDef.to())) {
                    missing.add(layerDef.id() + ": transition '" + transDef.id() + "' unknown to '" + transDef.to() + "'");
                }
                for (Id id : transDef.when()) {
                    if (!library.hasCondition(id)) missing.add(id.toString());
                }
                for (Id id : transDef.onFire()) {
                    if (!library.hasAction(id)) missing.add(id.toString());
                }
            }
        }
        if (!missing.isEmpty()) {
            LOGGER.warn("State machine '{}' has missing/invalid references: {}; degrading (condition->false, action->no-op, transition skipped)",
                    definition.id(), new ArrayList<>(missing));
        }
    }
}
