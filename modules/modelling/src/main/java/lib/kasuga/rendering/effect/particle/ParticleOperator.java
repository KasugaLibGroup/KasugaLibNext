package lib.kasuga.rendering.effect.particle;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * One-particle state function. Java lambdas and script bridges can implement this directly.
 */
@FunctionalInterface
public interface ParticleOperator extends UnaryOperator<ParticleState> {

    default ParticleOperator then(ParticleOperator next) {
        Objects.requireNonNull(next, "next");
        return state -> next.apply(apply(state));
    }
}
