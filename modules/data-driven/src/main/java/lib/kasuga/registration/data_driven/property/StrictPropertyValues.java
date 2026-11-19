package lib.kasuga.registration.data_driven.property;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/** Exact primitive decoders shared by strict block and item property validation. */
final class StrictPropertyValues {
    private StrictPropertyValues() {
    }

    static Integer exactInt(JsonElement value) {
        if (!number(value)) return null;
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    static Float finiteFloat(JsonElement value) {
        if (!number(value)) return null;
        try {
            BigDecimal decimal = value.getAsBigDecimal();
            float result = decimal.floatValue();
            return Float.isFinite(result) ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Boolean exactBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    static String exactString(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    static JsonArray exactArray(JsonElement value, int size) {
        if (value == null || !value.isJsonArray()) return null;
        JsonArray array = value.getAsJsonArray();
        return array.size() == size ? array : null;
    }

    private static boolean number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isNumber();
    }
}
