package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.registration.data_driven.state.StatePropertySet;

import javax.annotation.Nullable;

@NonNullFields
public record BlockDef(String id, String type, String registryGroup, JsonObject properties, JsonObject itemProperties,
                       @Nullable JsonObject params, @Nullable StatePropertySet stateProperties) {
    public BlockDef(String id, String type, String registryGroup, JsonObject properties, JsonObject itemProperties,
                    @Nullable JsonObject params) {
        this(id, type, registryGroup, properties, itemProperties, params, null);
    }
}
