package lib.kasuga.registration.data_driven.shape;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * Raw data-driven shape definition. Structural and semantic validation happens in
 * {@link ShapeDomainCompiler}, which can preserve the original JSON Pointer for diagnostics.
 */
public record ShapeDefinition(JsonObject json) {
    public ShapeDefinition {
        Objects.requireNonNull(json, "json");
        json = json.deepCopy();
    }

    public JsonElement get(String memberName) {
        return json.get(memberName);
    }

    public boolean has(String memberName) {
        return json.has(memberName);
    }
}
