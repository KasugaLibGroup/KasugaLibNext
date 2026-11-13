package lib.kasuga.core.resource.script;

import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.lifecycle.LogicalSide;
import java.util.Objects;

/** Static description, with location for errors. Constructing/decoding this never evaluates scripts. */
public record ScriptBlockRef(ResourceUri packageUri, String block, ScriptContract contract, LogicalSide side, String jsonPointer) {
    public ScriptBlockRef {
        Objects.requireNonNull(packageUri); Objects.requireNonNull(contract); Objects.requireNonNull(side); Objects.requireNonNull(jsonPointer);
        if (!packageUri.childKey().isEmpty() || block == null || !block.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}") || side == LogicalSide.BOTH)
            throw new IllegalArgumentException("Invalid script block reference at " + jsonPointer);
    }
}
