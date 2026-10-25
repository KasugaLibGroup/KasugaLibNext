package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationHost;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;

public interface ProxyHost extends AnimationHost {

    ProxyInstance<?> proxy();

    @Override
    default StateMachine<?> machine() {
        return proxy().machine();
    }
}
