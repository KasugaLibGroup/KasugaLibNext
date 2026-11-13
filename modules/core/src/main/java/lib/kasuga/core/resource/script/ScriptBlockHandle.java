package lib.kasuga.core.resource.script;

import java.util.List;

@FunctionalInterface
public interface ScriptBlockHandle {
    ScriptTicket submit(List<?> arguments);
}
