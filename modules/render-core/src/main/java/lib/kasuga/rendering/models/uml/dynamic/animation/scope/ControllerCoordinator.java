package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates controllers in stable registration order against one immutable pre-step context. */
public final class ControllerCoordinator {

    private final List<ControllerRoute> routes;

    public ControllerCoordinator(List<? extends ControllerRoute> routes) {
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    public static ControllerCoordinator empty() {
        return new ControllerCoordinator(List.of());
    }

    public ControllerRound evaluate(ControllerEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        List<ControllerStep> steps = new ArrayList<>(routes.size());
        try {
            for (ControllerRoute route : routes) {
                steps.add(route.evaluate(context));
            }
        } catch (RuntimeException failure) {
            for (int index = steps.size() - 1; index >= 0; index--) {
                try {
                    steps.get(index).discard();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        return new ControllerRound(steps);
    }
}
