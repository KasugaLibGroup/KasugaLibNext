package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;

import javax.annotation.Nullable;

@NonNullFields
public record ItemDef(String id, String type, String registryGroup, JsonObject properties, @Nullable JsonObject params) {}
