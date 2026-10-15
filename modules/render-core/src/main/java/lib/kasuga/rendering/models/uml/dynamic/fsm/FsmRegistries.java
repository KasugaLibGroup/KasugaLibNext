package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;

/**
 * Composition root for the FSM registry layer: the five collaborators — {@link FsmDefinitions}
 * (definition bucket), {@link FsmMachines} (scripting instance/handle bucket), {@link StateVarRegistry}
 * (typed state vars), {@link FsmFunctionLibrary} (guards/actions) and {@link FsmAnimationClips}
 * (animation clip bucket) — created and wired together in one place, replacing the former trio of
 * independent {@code GLOBAL} singletons.
 *
 * <p>{@link #GLOBAL} is the single process-wide instance backing the resource loader, the sync layer
 * and the scripting API. The wiring done here: when a definition is invalidated (overwritten, removed
 * or cleared), its inline state vars are dropped from the {@link StateVarRegistry} — this keeps the
 * definition bucket itself dependency-free and scopes inline-var cleanup to registries that share a
 * composition root (see {@link #create()} for isolated instances in tests).
 */
public final class FsmRegistries {

    /** Process-wide shared instance — the single entry point the loader, sync layer and scripting API bind to. */
    public static final FsmRegistries GLOBAL = create();

    private final FsmDefinitions definitions;
    private final FsmMachines machines;
    private final StateVarRegistry vars;
    private final FsmFunctionLibrary functions;
    private final FsmAnimationClips clips;

    private FsmRegistries(FsmDefinitions definitions, FsmMachines machines, StateVarRegistry vars, FsmFunctionLibrary functions, FsmAnimationClips clips) {
        this.definitions = definitions;
        this.machines = machines;
        this.vars = vars;
        this.functions = functions;
        this.clips = clips;
    }

    /**
     * Create a fresh, fully-wired set of registries. Definition invalidations clear the owning
     * {@link StateVarRegistry}'s inline vars for the invalidated machine id.
     */
    public static FsmRegistries create() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateVarRegistry vars = new StateVarRegistry();
        definitions.addListener(vars::clearForMachine);
        return new FsmRegistries(definitions, new FsmMachines(), vars, new FsmFunctionLibrary(), new FsmAnimationClips());
    }

    public FsmDefinitions definitions() {
        return definitions;
    }

    public FsmMachines machines() {
        return machines;
    }

    public StateVarRegistry vars() {
        return vars;
    }

    public FsmFunctionLibrary functions() {
        return functions;
    }

    public FsmAnimationClips clips() {
        return clips;
    }
}
