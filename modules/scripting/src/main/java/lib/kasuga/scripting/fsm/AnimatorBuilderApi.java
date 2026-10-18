package lib.kasuga.scripting.fsm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import jakarta.annotation.Nullable;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachines;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.PoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarType;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.security.Api;
import lib.kasuga.scripting.value.ScriptFunction;
import lib.kasuga.scripting.value.ScriptArray;
import lib.kasuga.scripting.value.ScriptObject;
import lib.kasuga.scripting.value.ScriptPrimitive;
import lib.kasuga.scripting.value.ScriptReference;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.scripting.value.ScriptValues;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Script-facing builder for the data-driven animation state machine. Scripts register guard
 * predicates and action callbacks by {@link Id}, then reference those locations in
 * JSON definitions loaded by {@link lib.kasuga.rendering.models.mc.dynamic.fsm.StateMachineDefinitionLoader}
 * or registered via {@link #registerDefinition(String)}.
 *
 * <p>Definitions and instances are written to the injected {@link FsmRegistries}; the default instance
 * binds {@link FsmRegistries#GLOBAL} (script machines only &mdash; host-owned machines must use their
 * own registry set). {@link #instantiate(String, Object)} builds a runtime machine from a
 * registered definition and returns the scripting handle.
 *
 * <p><b>Error philosophy (two-tier):</b> this builder API throws {@link IllegalArgumentException} on authoring
 * errors (invalid JSON in {@link #registerDefinition}, unknown id in {@link #instantiate}, invalid namespace)
 * — fail fast at registration time. The control API ({@link AnimatorApi}) is the opposite: it never throws
 * across the script boundary (it logs + no-ops on bad input).
 */
public final class AnimatorBuilderApi {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final FsmFunctionLibrary library;
    private final FsmDefinitions definitions;
    private final FsmMachines machines;
    private final StateVarRegistry stateVars;
    /**
     * Engine back-reference. Required by {@code ScriptFunction}-based callbacks ({@link #registerCondition}
     * / {@link #registerAction} JS overloads) to wrap the {@link StateContext} into a {@link ScriptValue}
     * before invoking the JS function. Nullable for Java-host callers that only use the
     * {@code Predicate}/{@code Consumer} overloads.
     */
    @Nullable
    private final ScriptEngine engine;
    /**
     * Pinned clones of JS callbacks registered via the {@code ScriptFunction} overloads, keyed by their
     * {@link Id}. Pinning keeps the underlying V8 function alive across ticks (otherwise the
     * engine could GC it between registration and first invocation). The clones belong to this engine's
     * runtime, so they are freed when the engine closes; re-registration replaces the entry.
     */
    private final Map<Id, ScriptFunction> pinnedCallbacks = new ConcurrentHashMap<>();

    public AnimatorBuilderApi() {
        this(FsmRegistries.GLOBAL, null);
    }

    public AnimatorBuilderApi(FsmRegistries registries) {
        this(registries, null);
    }

    /** Engine-aware ctor used by {@code FsmApiRegistration} (the supplier receives the engine). */
    public AnimatorBuilderApi(ScriptEngine engine) {
        this(FsmRegistries.GLOBAL, engine);
    }

    public AnimatorBuilderApi(@Nullable FsmRegistries registries, @Nullable ScriptEngine engine) {
        this.library = registries.functions();
        this.definitions = registries.definitions();
        this.machines = registries.machines();
        this.stateVars = registries.vars();
        this.engine = engine;
    }

    /**
     * Register a guard predicate from a Java host. JS authors must use the {@link #registerCondition(String,
     * String, ScriptFunction)} overload instead — the Javet bridge cannot synthesize a Java {@code Predicate}
     * from a JS function, so this overload is unreachable from JS ("Illegal invocation").
     */
    @Api
    public void registerCondition(String namespace, String path, Predicate<StateContext<?>> predicate) {
        Id id = Id.fromNamespaceAndPath(namespace, path);
        library.registerCondition(id, ctx -> predicate.test(ctx));
    }

    /**
     * Register an action from a Java host. JS authors must use the {@link #registerAction(String, String,
     * ScriptFunction)} overload — see {@link #registerCondition(String, String, Predicate)}.
     */
    @Api
    public void registerAction(String namespace, String path, Consumer<StateContext<?>> action) {
        Id id = Id.fromNamespaceAndPath(namespace, path);
        library.registerAction(id, ctx -> action.accept(ctx));
    }

    /**
     * Register a JS guard: the {@code guard} callback receives the {@link StateContext} (proxied to JS) and
     * returns a truthy/falsy value. This is the JS-main-scene path — the proven {@code ScriptFunction}
     * channel (same as {@code TimerModule.setInterval}) — the bridge {@code ScriptValue} fast path makes a
     * JS arrow function reachable here, unlike the {@code Predicate} overload. The function is cloned +
     * pinned so it survives across ticks; the clone is freed when the owning engine closes.
     *
     * @throws IllegalArgumentException if no engine is bound (Java-host callers must use the {@code Predicate} overload)
     */
    @Api
    public void registerCondition(String namespace, String path, ScriptFunction guard) {
        Id id = Id.fromNamespaceAndPath(namespace, path);
        ScriptFunction clone = pinForCallback(id, guard);
        library.registerCondition(id, ctx -> {
            try {
                ScriptValue result = clone.execute(engine.createValue(new ScriptFsmContext(ctx, stateVars)));
                return ScriptValues.isTrue(result);
            } catch (Exception e) {
                LOGGER.warn("FSM guard '{}' threw; degrading to false", id, e);
                return false;
            }
        });
    }

    /**
     * Register a JS action: the {@code action} callback receives the {@link StateContext} (proxied to JS).
     * See {@link #registerCondition(String, String, ScriptFunction)} for the bridge rationale and lifecycle.
     *
     * @throws IllegalArgumentException if no engine is bound (Java-host callers must use the {@code Consumer} overload)
     */
    @Api
    public void registerAction(String namespace, String path, ScriptFunction action) {
        Id id = Id.fromNamespaceAndPath(namespace, path);
        ScriptFunction clone = pinForCallback(id, action);
        library.registerAction(id, ctx -> {
            try {
                clone.executeVoid(engine.createValue(new ScriptFsmContext(ctx, stateVars)));
            } catch (Exception e) {
                LOGGER.warn("FSM action '{}' threw; swallowed", id, e);
            }
        });
    }

    /**
     * Clone + pin a JS callback so it stays alive across ticks, remembering it by {@code id} so a
     * re-registration replaces the prior clone (the old clone is released when the engine closes).
     */
    private ScriptFunction pinForCallback(Id id, ScriptFunction callback) {
        if (engine == null) {
            throw new IllegalArgumentException(
                    "ScriptFunction callbacks require an engine back-reference; use the Predicate/Consumer overload from Java hosts");
        }
        try {
            ScriptFunction clone = (ScriptFunction) callback.cloneValue();
            ScriptReference.pinFor(clone);
            pinnedCallbacks.put(id, clone);
            return clone;
        } catch (ScriptException e) {
            throw new IllegalArgumentException("Failed to pin JS callback for '" + id + "'", e);
        }
    }

    //region state vars

    /**
     * Register a typed {@link StateVar} from a script. {@code type} is a built-in token
     * ({@code "bool"/"int"/"float"/"string"/"resource"/"vec3"} — see {@link #varTypes()}); {@code defaultValue}
     * is coerced to that type (JS numbers arrive as Integer/Double), falling back to the type's zero value when
     * null or uncoerceable. {@code ephemeral=true} marks a tick-scoped trigger var (cleared at the end of every
     * tick). Returns the registered id ({@code "namespace:path"}), or {@code ""} on an unknown type. Idempotent:
     * re-registering an existing id is a no-op that returns the same id.
     */
    @Api
    public String registerStateVar(String namespace, String path, String type, Object defaultValue, boolean ephemeral) {
        Id id = Id.fromNamespaceAndPath(namespace, path);
        StateVarType<?> varType;
        try {
            varType = StateVarType.byToken(type);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("registerStateVar({}, {}): unknown type '{}'", namespace, path, type);
            return "";
        }
        if (stateVars.has(id)) {
            StateVar<?> existing = stateVars.get(id);
            StateVar<?> proposed = buildVar(id, varType, defaultValue, ephemeral);
            if (!StateVarRegistry.compatible(existing, proposed)) {
                LOGGER.warn("registerStateVar({}, {}): id already registered with a different type/default/ephemeral; keeping the existing registration",
                        namespace, path);
            }
            return id.toString();
        }
        stateVars.register(buildVar(id, varType, defaultValue, ephemeral));
        return id.toString();
    }

    /** Shortcut: non-ephemeral var with an explicit default value. */
    @Api
    public String registerStateVar(String namespace, String path, String type, Object defaultValue) {
        return registerStateVar(namespace, path, type, defaultValue, false);
    }

    /** Shortcut: non-ephemeral var with the type's zero default. */
    @Api
    public String registerStateVar(String namespace, String path, String type) {
        return registerStateVar(namespace, path, type, null, false);
    }

    /** The built-in {@link StateVarType} tokens scripts may pass to {@link #registerStateVar}. */
    @Api
    public String[] varTypes() {
        return StateVarType.all().stream()
                .map(StateVarType::token)
                .toArray(String[]::new);
    }

    private static <T> StateVar<T> buildVar(Id id, StateVarType<T> type, Object defaultValue, boolean ephemeral) {
        T resolved;
        if (defaultValue == null) {
            resolved = type.zeroDefault();
        } else {
            T coerced = StateValueCoercer.coerce(type, defaultValue);
            resolved = coerced != null ? coerced : type.zeroDefault();
        }
        StateVar.Builder<T> builder = StateVar.builder(id, type.type(), type.codec()).defaultValue(resolved);
        if (ephemeral) {
            builder.ephemeral();
        }
        return builder.build();
    }

    //endregion

    @Api
    public String registerDefinition(String json) {
        JsonElement element = JsonParser.parseString(json);
        return decodeAndRegister(element);
    }

    /**
     * Register a definition from a JS object literal directly ({@code registerDefinition({ id: "...", ... })}),
     * so authors don't have to hand-write {@code JSON.stringify(...)}. Also accepts a JS string (a
     * {@code JSON.stringify(...)}'d payload) for back-compat — the bridge's per-arity convertMask makes this
     * the overload JS always hits when a {@code ScriptValue} form exists, so it handles both shapes. The
     * object is walked into a {@link JsonElement} on the Java side; a string is parsed. Throws
     * {@link IllegalArgumentException} on an invalid definition (same two-tier error philosophy as the
     * string overload, which remains for Java hosts).
     */
    @Api
    public String registerDefinition(ScriptValue definition) {
        JsonElement element;
        // Top-level JSON.stringify'd string (the legacy JS form) → parse; object literal → walk.
        if (definition instanceof ScriptPrimitive primitive) {
            try {
                Object unwrapped = primitive.getValue();
                if (unwrapped instanceof String s) {
                    element = JsonParser.parseString(s);
                } else {
                    element = toJsonElement(definition);
                }
            } catch (ScriptException e) {
                throw new IllegalArgumentException("Failed to read definition value", e);
            }
        } else {
            element = toJsonElement(definition);
        }
        return decodeAndRegister(element);
    }

    private String decodeAndRegister(JsonElement element) {
        return StateMachineDefinition.CODEC.decode(JsonOps.INSTANCE, element)
                .resultOrPartial(error -> { throw new IllegalArgumentException("Invalid state machine JSON: " + error); })
                .map(result -> {
                    StateMachineDefinition definition = result.getFirst();
                    definitions.register(definition.id(), definition);
                    return definition.id().toString();
                })
                .orElse("");
    }

    /**
     * Walk a {@link ScriptValue} (JS object/array/primitive/null) into the Gson tree the FSM codec expects.
     * String primitives are kept as JSON string values — only the {@link #registerDefinition(ScriptValue)}
     * top-level arg may be a {@code JSON.stringify}'d string (parsed there, not here).
     */
    private static JsonElement toJsonElement(ScriptValue value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof ScriptPrimitive primitive) {
            try {
                Object unwrapped = primitive.getValue();
                if (unwrapped == null) {
                    return JsonNull.INSTANCE;
                }
                if (unwrapped instanceof Number n) {
                    return new JsonPrimitive(n);
                }
                if (unwrapped instanceof Boolean b) {
                    return new JsonPrimitive(b);
                }
                if (unwrapped instanceof String s) {
                    return new JsonPrimitive(s);
                }
                if (unwrapped instanceof Character c) {
                    return new JsonPrimitive(c);
                }
                return new JsonPrimitive(unwrapped.toString());
            } catch (ScriptException e) {
                throw new IllegalArgumentException("Failed to read primitive value", e);
            }
        }
        if (value instanceof ScriptArray array) {
            JsonArray out = new JsonArray();
            try {
                for (ScriptValue element : array.asArray()) {
                    out.add(toJsonElement(element));
                }
            } catch (ScriptException e) {
                throw new IllegalArgumentException("Failed to read array", e);
            }
            return out;
        }
        if (value instanceof ScriptObject object) {
            JsonObject out = new JsonObject();
            try {
                for (ScriptValue key : object.getObjectKeys()) {
                    out.add(key.asString(), toJsonElement(object.getMember(key)));
                }
            } catch (ScriptException e) {
                throw new IllegalArgumentException("Failed to read object", e);
            }
            return out;
        }
        // Functions etc. — stringify as a fallback (will fail codec validation with a clear error).
        try {
            return new JsonPrimitive(value.asString());
        } catch (ScriptException e) {
            return JsonNull.INSTANCE;
        }
    }

    /**
     * Instantiate a runtime machine from a previously registered definition and obtain its scripting
     * handle. Logic-only, no pose sink; use the three-argument overload to attach one for a
     * {@link ModelInstance}. Each machine must be ticked from a single thread
     * ({@link AnimatorApi#tick(int)}); host-owned machines must never be ticked through the
     * scripting API.
     *
     * @throws IllegalArgumentException if no definition is registered under {@code id}
     */
    @Api
    public int instantiate(String id, Object owner) {
        return instantiate(id, owner, null);
    }

    /**
     * Instantiate a runtime machine from a previously registered definition and obtain its scripting
     * handle. A {@link ModelInstance} attaches a {@link ModelInstancePoseSink} (client-side
     * rendering); other non-null models are logged as a warning and the machine runs without a pose
     * sink. Each machine must be ticked from a single thread; host-owned machines must never be
     * ticked through the scripting API.
     *
     * @throws IllegalArgumentException if no definition is registered under {@code id}
     */
    @Api
    public int instantiate(String id, Object owner, Object model) {
        Id location = Id.tryParse(id);
        StateMachineDefinition definition = location == null ? null : definitions.get(location);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown state machine definition: " + id);
        }

        PoseSink sink = null;
        if (model instanceof ModelInstance instance) {
            sink = new ModelInstancePoseSink(instance);
        } else if (model != null) {
            LOGGER.warn("instantiate({}): model {} is not a ModelInstance; machine will run without a pose sink",
                    id, model.getClass().getName());
        }

        StateMachine<Object> machine = new DefinitionStateMachineFactory<Object>(library, stateVars).build(owner, definition, sink);
        return (int) machines.register(definition.id(), machine);
    }
}
