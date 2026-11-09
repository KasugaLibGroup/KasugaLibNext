package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationHost;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;

/**
 * Legacy MC host bridge retained for unmigrated content.
 *
 * @deprecated New content should implement {@link ProxySessionHost}; this bridge exposes
 * {@link StateMachine} through {@link AnimationHost} and is not the kernel boundary.
 */
@Deprecated(forRemoval = false)
public interface ProxyHost extends AnimationHost {

    ProxyInstance<?> proxy();

    @Override
    default StateMachine<?> machine() {
        return proxy().machine();
    }
}
