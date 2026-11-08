package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;

@NonNullFields
public record BlockEntityDef(@SerializedName("type") String beType, @SerializedName("_parent_block") String parentBlockId, @Nullable JsonObject params) {}
