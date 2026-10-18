package lib.kasuga.scripting.fsm;

import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptEngineType;

/**
 * Installs the FSM global APIs ("Animator" + "AnimatorBuilder") onto a {@link ScriptEngineType.Builder}.
 * Call this from your engine-type builder (e.g. the Javet engine) so every created engine registers
 * the globals via {@code ScriptEngineType.create}.
 */
public final class FsmApiRegistration {

    private FsmApiRegistration() {}

    public static <T extends ScriptEngine> ScriptEngineType.Builder<T> install(ScriptEngineType.Builder<T> builder) {
        // Engine-aware suppliers: AnimatorApi/AnimatorBuilderApi capture the engine back-reference so they
        // can wrap Java objects (StateContext) into ScriptValues when invoking JS callbacks. Explicit
        // lambdas (not ctor references) avoid Supplier/Function overload ambiguity.
        return builder
                .addGlobalApi("Animator", engine -> new AnimatorApi(engine))
                .addGlobalApi("AnimatorBuilder", engine -> new AnimatorBuilderApi(engine));
    }
}
