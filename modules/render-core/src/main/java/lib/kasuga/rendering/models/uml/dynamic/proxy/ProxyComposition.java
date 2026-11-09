package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;

import java.util.List;
import java.util.Objects;

/**
 * One indivisible machine/model/parts composition for an element. All ports are declared together
 * so a session cannot choose a machine and visual from unrelated owners.
 */
public record ProxyComposition<T, I, O, C, F>(
        LogicalStepPolicy policy,
        ProxyInputPort<T, I> inputPort,
        ProxyControllerPort<I, O> controllerPort,
        ProxyWritebackPort<T, O> writebackPort,
        ProxyCommandPort<T, C> commandPort,
        ProxyVisualPort<T, F> visualPort,
        List<TargetBinding<?>> targets
) {

    public ProxyComposition {
        policy = Objects.requireNonNull(policy, "policy");
        inputPort = Objects.requireNonNull(inputPort, "inputPort");
        controllerPort = Objects.requireNonNull(controllerPort, "controllerPort");
        writebackPort = Objects.requireNonNull(writebackPort, "writebackPort");
        commandPort = Objects.requireNonNull(commandPort, "commandPort");
        visualPort = Objects.requireNonNull(visualPort, "visualPort");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    public static <T, I, O, C, F> ProxyComposition<T, I, O, C, F> of(
            LogicalStepPolicy policy,
            ProxyInputPort<T, I> inputPort,
            ProxyControllerPort<I, O> controllerPort,
            ProxyWritebackPort<T, O> writebackPort,
            ProxyCommandPort<T, C> commandPort,
            ProxyVisualPort<T, F> visualPort,
            List<TargetBinding<?>> targets
    ) {
        return new ProxyComposition<>(
                policy, inputPort, controllerPort, writebackPort,
                commandPort, visualPort, targets);
    }
}
