package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns stable target addresses and routes committed logical results to target endpoints.
 *
 * <p>The registry preserves registration order, evaluates and publishes each target once per
 * revision, isolates a failing target, and keeps target lifecycle management in one place.
 */
public final class TargetRegistry implements AnimationRenderPort, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TargetRegistry.class);

    private final Object lock = new Object();
    private final Map<AnimationTargetRef, TargetBinding<?>> bindings = new LinkedHashMap<>();
    private volatile RenderBatch latestBatch = RenderBatch.empty();
    private volatile RenderFrameToken lastSampledFrame;
    private boolean closed;

    public void register(TargetBinding<?> binding) {
        Objects.requireNonNull(binding, "binding");
        AnimationTargetRef ref = Objects.requireNonNull(binding.ref(), "target ref");
        synchronized (lock) {
            requireOpen();
            if (bindings.containsKey(ref)) {
                throw new IllegalArgumentException("target already registered: " + ref);
            }
            bindings.put(ref, binding);
        }
    }

    public void publish(TargetEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        synchronized (lock) {
            requireOpen();
            List<RenderEntry<?>> rendered = new ArrayList<>(bindings.size());
            for (Map.Entry<AnimationTargetRef, TargetBinding<?>> entry : bindings.entrySet()) {
                try {
                    rendered.add(evaluateAndPublish(entry.getValue(), entry.getKey(), context));
                } catch (RuntimeException failure) {
                    LOG.error(
                            "Target {} failed while publishing revision {}",
                            entry.getKey(),
                            context.revision(),
                            failure);
                    RenderEntry<?> previous = previousEntry(entry.getKey());
                    if (previous != null) {
                        rendered.add(previous);
                    }
                }
            }
            latestBatch = new RenderBatch(context.revision(), List.copyOf(rendered));
        }
    }

    /**
     * Samples exactly one completed publication batch.
     *
     * <p>This method does not take the registry lock. A concurrent publish can complete, but the
     * render call retains the batch captured at entry and therefore cannot observe two revisions.
     */
    @Override
    public synchronized void sample(RenderSampleContext context) {
        Objects.requireNonNull(context, "context");
        if (context.frameToken().equals(lastSampledFrame)) {
            return;
        }
        lastSampledFrame = context.frameToken();
        RenderBatch batch = latestBatch;
        for (RenderEntry<?> entry : batch.entries()) {
            try {
                sampleEntry(entry, context);
            } catch (RuntimeException failure) {
                LOG.error(
                        "Target {} failed while sampling revision {}",
                        entry.publication().ref(),
                        batch.revision(),
                        failure);
            }
        }
    }

    /**
     * Returns an immutable snapshot used by the model-level render entry point.
     */
    public List<AnimationTarget<?>> renderTargets() {
        synchronized (lock) {
            List<AnimationTarget<?>> targets = new ArrayList<>(bindings.size());
            for (TargetBinding<?> binding : bindings.values()) {
                targets.add(binding.target());
            }
            return List.copyOf(targets);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;

            IllegalStateException aggregate = null;
            for (TargetBinding<?> binding : bindings.values()) {
                try {
                    binding.target().close();
                } catch (RuntimeException failure) {
                    if (aggregate == null) {
                        aggregate = new IllegalStateException("one or more targets failed to close", failure);
                    } else {
                        aggregate.addSuppressed(failure);
                    }
                }
            }
            bindings.clear();
            latestBatch = RenderBatch.empty();
            lastSampledFrame = null;

            if (aggregate != null) {
                throw aggregate;
            }
        }
    }

    private static <O> RenderEntry<O> evaluateAndPublish(
            TargetBinding<O> binding,
            AnimationTargetRef ref,
            TargetEvaluationContext context
    ) {
        O currentOutput = binding.evaluator().evaluate(context);
        OutputState<O> outputState = binding.factory().create(
                context, currentOutput, currentOutput != null);
        TargetPublication<O> publication = new TargetPublication<>(
                ref,
                context.revision(),
                outputState);
        binding.target().publish(publication);
        binding.factory().commit(outputState);
        return new RenderEntry<>(binding.target(), publication);
    }

    private static <O> void sampleEntry(RenderEntry<O> entry, RenderSampleContext context) {
        entry.target().sample(entry.publication(), context);
    }

    private RenderEntry<?> previousEntry(AnimationTargetRef ref) {
        for (RenderEntry<?> entry : latestBatch.entries()) {
            if (entry.publication().ref().equals(ref)) {
                return entry;
            }
        }
        return null;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("target registry is closed");
        }
    }

    private record RenderEntry<O>(
            AnimationTarget<O> target,
            TargetPublication<O> publication
    ) {
    }

    private record RenderBatch(long revision, List<RenderEntry<?>> entries) {

        private static RenderBatch empty() {
            return new RenderBatch(-1, List.of());
        }
    }
}
