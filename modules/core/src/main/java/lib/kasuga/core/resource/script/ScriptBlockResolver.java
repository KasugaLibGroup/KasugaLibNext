package lib.kasuga.core.resource.script;

import lib.kasuga.core.resource.lifecycle.InvocationContext;
import java.util.concurrent.CompletionStage;

/** Neutral SPI: data-driven/modelling consumers do not depend on a concrete scripting module. */
@FunctionalInterface
public interface ScriptBlockResolver {
    CompletionStage<ScriptBlockHandle> bind(ScriptBlockRef reference, InvocationContext context);
}
