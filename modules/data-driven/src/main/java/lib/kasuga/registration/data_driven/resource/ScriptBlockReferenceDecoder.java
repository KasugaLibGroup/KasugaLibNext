package lib.kasuga.registration.data_driven.resource;

import com.google.gson.JsonObject;
import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.lifecycle.LogicalSide;
import lib.kasuga.core.resource.script.ScriptBlockRef;
import lib.kasuga.core.resource.script.ScriptReferenceCodec;
import java.util.function.Predicate;

/** Used only by handlers that explicitly declare a script-block field. */
public final class ScriptBlockReferenceDecoder {
    private ScriptBlockReferenceDecoder() {}
    public static ScriptBlockRef decode(JsonObject field, String jsonPointer, LogicalSide side, Predicate<ResourceUri> visible) {
        return ScriptReferenceCodec.decode(field, jsonPointer, side, visible);
    }
}
