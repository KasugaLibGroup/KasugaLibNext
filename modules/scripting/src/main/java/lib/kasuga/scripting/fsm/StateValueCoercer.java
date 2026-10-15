package lib.kasuga.scripting.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Marshals script (JS/V8) values to/from typed
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar} values at the scripting boundary.
 *
 * <p>JS numbers arrive as {@code Integer} or {@code Double}; {@link #coerce} widens them safely (integral
 * {@code Double} → {@code int}, any {@code Number} → {@code float}) and returns {@code null} for wrong-kind
 * values so callers can log + no-op rather than throw across the boundary. {@link #box} converts stored
 * values back to script-friendly forms ({@code ResourceLocation} → string, {@code Vector3f} → {@code [x,y,z]}).
 */
public final class StateValueCoercer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private StateValueCoercer() {}

    /**
     * Coerce a script value to {@code type}, or {@code null} if it cannot be coerced. Used by
     * {@link AnimatorApi#set} (writes) and {@link AnimatorBuilderApi#registerStateVar} (default values).
     */
    @SuppressWarnings("unchecked")
    public static <T> T coerce(StateVarType<T> type, Object value) {
        Class<?> cls = type.type();
        Object result;
        if (cls == Boolean.class) {
            result = value instanceof Boolean b ? b : null;
        } else if (cls == Integer.class) {
            if (value instanceof Integer i) {
                result = i;
            } else if (value instanceof Double d && !d.isInfinite() && !d.isNaN() && d == Math.floor(d)) {
                result = d.intValue();
            } else {
                result = null;
            }
        } else if (cls == Float.class) {
            if (value instanceof Float f) {
                result = f;
            } else if (value instanceof Double d) {
                result = d.floatValue();
            } else if (value instanceof Integer i) {
                result = i.floatValue();
            } else {
                result = null;
            }
        } else if (cls == String.class) {
            result = value instanceof String s ? s : null;
        } else if (cls == ResourceLocation.class) {
            if (value instanceof ResourceLocation rl) {
                result = rl;
            } else if (value instanceof String s) {
                result = ResourceLocation.tryParse(s);
            } else {
                result = null;
            }
        } else if (cls == Vector3f.class) {
            result = coerceVec3(value);
        } else {
            result = null;
        }
        return (T) result;
    }

    /** Box a stored value for return to the script. */
    public static Object box(Object value) {
        if (value instanceof ResourceLocation rl) {
            return rl.toString();
        }
        if (value instanceof Vector3f v) {
            return List.of(v.x, v.y, v.z);
        }
        return value;
    }

    public static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static Vector3f coerceVec3(Object value) {
        if (value instanceof Vector3f v) {
            return v;
        }
        if (value instanceof List<?> list && list.size() == 3) {
            return new Vector3f(asFloat(list.get(0)), asFloat(list.get(1)), asFloat(list.get(2)));
        }
        if (value instanceof Map<?, ?> map && map.containsKey("x") && map.containsKey("y") && map.containsKey("z")) {
            return new Vector3f(asFloat(map.get("x")), asFloat(map.get("y")), asFloat(map.get("z")));
        }
        return null;
    }

    private static float asFloat(Object value) {
        if (value instanceof Number n) {
            return n.floatValue();
        }
        LOGGER.warn("vec3 element '{}' is not a number; using 0", value);
        return 0f;
    }
}
