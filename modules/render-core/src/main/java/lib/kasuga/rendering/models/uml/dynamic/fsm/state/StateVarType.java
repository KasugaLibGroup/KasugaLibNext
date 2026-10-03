package lib.kasuga.rendering.models.uml.dynamic.fsm.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in {@link StateVar} value types — the catalog the data-driven codec dispatches on by token
 * (see {@code StateVarDefinition#type()}). Each entry bundles the {@link Codec} and {@link Class} token the
 * factory and the script boundary need to (de)serialize and runtime-type-check values.
 *
 * <p>The catalog is a <b>mutable</b> registry seeded with the pure (MC-free) types ({@link #BOOL},
 * {@link #INT}, {@link #FLOAT}, {@link #STRING}, {@link #VEC3}). The Minecraft layer can re-add MC-typed
 * entries (e.g. a resource-location value type) via {@link #register(StateVarType)} so this uml.fsm package
 * keeps no {@code net.minecraft.*} dependency.
 */
public record StateVarType<T>(String token, Class<T> type, Codec<T> codec) {

    public static final StateVarType<Boolean> BOOL = new StateVarType<>("bool", Boolean.class, Codec.BOOL);
    public static final StateVarType<Integer> INT = new StateVarType<>("int", Integer.class, Codec.INT);
    public static final StateVarType<Float> FLOAT = new StateVarType<>("float", Float.class, Codec.FLOAT);
    public static final StateVarType<String> STRING = new StateVarType<>("string", String.class, Codec.STRING);
    public static final StateVarType<Vector3f> VEC3 = new StateVarType<>("vec3", Vector3f.class, vec3Codec());

    private static final Map<String, StateVarType<?>> BY_TOKEN = buildCatalog();

    private static Codec<Vector3f> vec3Codec() {
        return Codec.FLOAT.listOf().comapFlatMap(
                list -> list.size() == 3
                        ? DataResult.success(new Vector3f(list.get(0), list.get(1), list.get(2)))
                        : DataResult.error(() -> "vec3 expects 3 floats, got " + list.size()),
                value -> List.of(value.x, value.y, value.z)
        );
    }

    private static Map<String, StateVarType<?>> buildCatalog() {
        Map<String, StateVarType<?>> map = new ConcurrentHashMap<>();
        map.put(BOOL.token(), BOOL);
        map.put(INT.token(), INT);
        map.put(FLOAT.token(), FLOAT);
        map.put(STRING.token(), STRING);
        map.put(VEC3.token(), VEC3);
        return map;
    }

    /**
     * Register an additional {@link StateVarType} (e.g. an MC-typed entry such as a resource-location value
     * type) so the data-driven codec and script boundary can dispatch on its token. Idempotent overwrite by
     * token.
     */
    public static void register(StateVarType<?> type) {
        BY_TOKEN.put(type.token(), type);
    }

    /** Resolve a type by its serialization token; throws on unknown tokens. */
    public static StateVarType<?> byToken(String token) {
        StateVarType<?> type = BY_TOKEN.get(token);
        if (type == null) {
            throw new IllegalArgumentException("unknown state var type: " + token + "; valid: " + BY_TOKEN.keySet());
        }
        return type;
    }

    /** Resolve a built-in type by its value class, or {@code null} if no built-in matches. */
    public static StateVarType<?> byClass(Class<?> type) {
        for (StateVarType<?> candidate : BY_TOKEN.values()) {
            if (candidate.type().equals(type)) {
                return candidate;
            }
        }
        return null;
    }

    public static Collection<StateVarType<?>> all() {
        return BY_TOKEN.values();
    }

    /**
     * A sensible zero/default value for this type ({@code false}, {@code 0}, {@code 0f}, {@code ""},
     * {@code (0,0,0)}). Used when a var is declared without an explicit default.
     */
    @SuppressWarnings("unchecked")
    public T zeroDefault() {
        Object value;
        if (this == BOOL) {
            value = Boolean.FALSE;
        } else if (this == INT) {
            value = 0;
        } else if (this == FLOAT) {
            value = 0f;
        } else if (this == STRING) {
            value = "";
        } else if (this == VEC3) {
            value = new Vector3f();
        } else {
            value = null;
        }
        return (T) value;
    }
}
