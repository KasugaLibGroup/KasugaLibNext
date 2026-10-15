package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;

/**
 * Marker owners implement to expose their {@link StateMachine} via a NeoForge capability.
 * A block entity or entity implements this and returns its (typed) machine; the cap provider unwraps it.
 */
public interface AnimationHost {
    StateMachine<?> machine();
}
