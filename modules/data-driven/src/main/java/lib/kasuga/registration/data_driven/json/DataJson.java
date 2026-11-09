package lib.kasuga.registration.data_driven.json;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.state.StatePropertySet;
import lib.kasuga.utils.json.GsonBindings;

/** Typed resource bindings; factory-specific params remain an extensible JSON tree. */
public final class DataJson {
    public static final Gson GSON = GsonBindings.builder()
            .registerTypeAdapter(StatePropertySet.class, (JsonDeserializer<StatePropertySet>)
                    (json, type, context) -> StatePropertySet.parse(json))
            .create();
    private DataJson() {}

    public static String creativeTab(JsonObject properties) {
        return properties == null ? null : GSON.fromJson(properties, ItemMetadata.class).tab;
    }
    private static final class ItemMetadata { String tab; }
}
