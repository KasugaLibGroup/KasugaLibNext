package lib.kasuga.core.resource.script;

import com.google.gson.*;
import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.lifecycle.LogicalSide;
import java.util.*;
import java.util.function.Predicate;
import static lib.kasuga.core.resource.routing.StrictResourceJson.*;

/** Strict opt-in data field decoder. No resolver or engine can be invoked here. */
public final class ScriptReferenceCodec {
    private ScriptReferenceCodec() {}
    public static ScriptBlockRef decode(JsonObject value, String pointer, LogicalSide side, Predicate<ResourceUri> visible) {
        fields(value, "package", "block", "contract", "side");
        ResourceUri owner = ResourceUri.parse(string(value, "package"));
        if (!visible.test(owner)) throw new IllegalArgumentException("PERMISSION_DENIED at " + pointer);
        LogicalSide requested = LogicalSide.parse(string(value, "side"));
        if (requested != side) throw new IllegalArgumentException("Logical side mismatch at " + pointer);
        return new ScriptBlockRef(owner, string(value, "block"), contract(object(value.get("contract"))), requested, pointer);
    }
    public static ScriptContract contract(JsonObject value) {
        fields(value, "arguments", "result");
        List<ScriptContract.Type> arguments = new ArrayList<>();
        if (!value.has("arguments")) throw new IllegalArgumentException("Expected arguments");
        for (JsonElement type : array(value, "arguments")) {
            if (!(type instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException("Expected argument type");
            arguments.add(ScriptContract.Type.valueOf(p.getAsString().toUpperCase(Locale.ROOT)));
        }
        return new ScriptContract(arguments, ScriptContract.Type.valueOf(string(value, "result").toUpperCase(Locale.ROOT)));
    }
}
