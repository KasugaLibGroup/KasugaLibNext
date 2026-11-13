package lib.kasuga.core.resource.script;

import java.util.*;

/** Engine-independent data contract. Host/native objects and non-finite numbers never cross the ring. */
public record ScriptContract(List<Type> arguments, Type result) {
    public enum Type { NULL, BOOLEAN, NUMBER, STRING, ARRAY, OBJECT, VOID }
    public ScriptContract {
        arguments = List.copyOf(arguments); Objects.requireNonNull(result);
        if (arguments.size() > 64 || arguments.contains(Type.VOID)) throw new IllegalArgumentException("Invalid script arguments");
    }
    public List<Object> freezeArguments(List<?> values) {
        if (values.size() != arguments.size()) throw new IllegalArgumentException("Script argument count mismatch");
        List<Object> copy = new ArrayList<>(); int[] budget = {16_384, 1_048_576};
        for (int i = 0; i < values.size(); i++) { Object frozen = freeze(values.get(i), 0, budget); check(arguments.get(i), frozen); copy.add(frozen); }
        return Collections.unmodifiableList(copy);
    }
    public Object freezeResult(Object value) {
        if (result == Type.VOID) { if (value != null) throw new IllegalArgumentException("Expected void result"); return null; }
        Object frozen = freeze(value, 0, new int[]{16_384, 1_048_576}); check(result, frozen); return frozen;
    }
    private static Object freeze(Object value, int depth, int[] budget) {
        if (depth > 32 || --budget[0] < 0) throw new IllegalArgumentException("LIMIT_EXCEEDED: script value budget");
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof String string) {
            if ((budget[1] -= string.length()) < 0) throw new IllegalArgumentException("LIMIT_EXCEEDED: string budget");
            return string;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite script value");
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(); for (Object item : list) copy.add(freeze(item, depth + 1, budget));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Script object keys must be strings");
                copy.put((String) freeze(key, depth + 1, budget), freeze(entry.getValue(), depth + 1, budget));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("Host/native values are not script data: " + value.getClass().getName());
    }
    private static void check(Type type, Object value) {
        boolean valid = switch (type) {
            case NULL, VOID -> value == null;
            case BOOLEAN -> value instanceof Boolean;
            case NUMBER -> value instanceof Number;
            case STRING -> value instanceof String;
            case ARRAY -> value instanceof List<?>;
            case OBJECT -> value instanceof Map<?, ?>;
        };
        if (!valid) throw new IllegalArgumentException("Script contract expected " + type);
    }
}
