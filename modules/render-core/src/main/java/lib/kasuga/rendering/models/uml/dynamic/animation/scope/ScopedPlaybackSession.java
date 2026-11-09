package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the mutable target/evaluator attachment for one private Scope playback. */
public final class ScopedPlaybackSession<A extends AnimationTarget<?>, E> {

    private final AtomicReference<Attachment<A, E>> attachment = new AtomicReference<>();

    public void attach(ManagedAnimationScope nextScope, A nextTarget, E nextEvaluator) {
        attachment.set(new Attachment<>(nextScope, nextTarget, nextEvaluator));
    }

    public ManagedAnimationScope scope() {
        Attachment<A, E> current = attachment.get();
        return current == null ? null : current.scope();
    }

    public A target() {
        Attachment<A, E> current = attachment.get();
        return current == null ? null : current.target();
    }

    public E evaluator() {
        Attachment<A, E> current = attachment.get();
        return current == null ? null : current.evaluator();
    }

    public void clear() {
        attachment.set(null);
    }

    public void closeScope() {
        Attachment<A, E> current = attachment.getAndSet(null);
        ScopedPlaybackSupport.close(current == null ? null : current.scope());
    }

    private record Attachment<A extends AnimationTarget<?>, E>(
            ManagedAnimationScope scope,
            A target,
            E evaluator
    ) {
        private Attachment {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(evaluator, "evaluator");
        }
    }
}
