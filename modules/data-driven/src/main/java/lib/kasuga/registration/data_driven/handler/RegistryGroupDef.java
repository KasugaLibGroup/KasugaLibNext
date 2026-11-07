package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;

@NonNullFields
public record RegistryGroupDef(String id, String parent, JsonObject properties, JsonObject itemProperties) {}
