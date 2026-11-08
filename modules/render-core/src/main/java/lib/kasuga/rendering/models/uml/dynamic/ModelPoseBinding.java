package lib.kasuga.rendering.models.uml.dynamic;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Owns the single internal pose driver installed on a {@link ModelInstance}.
 *
 * <p>The binding is the value stored in {@link ModelInstance#getPoseDriver()}. It forwards each
 * tick and render sample once to one source, and keeps resource-reload rebinding in one place:
 * the source is moved first; only a successful move detaches the old instance and installs the
 * fresh one.
 */
public final class ModelPoseBinding implements RebindablePoseDriver, AutoCloseable {

    private final PoseDriver source;
    private volatile ModelInstance model;
    private volatile boolean closed;

    private ModelPoseBinding(ModelInstance model, PoseDriver source) {
        this.model = Objects.requireNonNull(model, "model");
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Installs {@code source} behind a binding. Repeating the call with the same source and model
     * reinstalls the existing binding instead of creating a second owner.
     */
    public static synchronized ModelPoseBinding install(ModelInstance model, PoseDriver source) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(source, "source");
        ModelPoseBinding current = installedFor(model, source);
        if (current != null) {
            current.install();
            return current;
        }
        if (model.getPoseDriver() instanceof ModelPoseBinding previous) {
            previous.close();
        }
        ModelPoseBinding binding = new ModelPoseBinding(model, source);
        binding.install();
        return binding;
    }

    /** Returns the binding already installed on {@code model} for {@code source}, if any. */
    public static @Nullable ModelPoseBinding installedFor(ModelInstance model, PoseDriver source) {
        if (model.getPoseDriver() instanceof ModelPoseBinding binding && binding.source == source) {
            return binding;
        }
        return null;
    }

    /** The wrapped animation source. */
    public PoseDriver source() {
        return source;
    }

    /** The instance this binding currently installs on, or {@code null} after {@link #close()}. */
    public @Nullable ModelInstance model() {
        return model;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void tick(float deltaSeconds) {
        if (!closed) {
            source.tick(deltaSeconds);
        }
    }

    @Override
    public void sample(float partialTick) {
        if (closed) {
            return;
        }
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("partialTick must be finite");
        }
        source.sample(partialTick);
    }

    /** Moves this binding and its source to a freshly materialized model instance. */
    @Override
    public synchronized void rebind(ModelInstance fresh) {
        Objects.requireNonNull(fresh, "fresh");
        if (closed) {
            throw new IllegalStateException("model pose binding is closed");
        }
        if (!(source instanceof RebindablePoseDriver)) {
            throw new IllegalStateException("pose driver does not support rebind: "
                    + source.getClass().getName());
        }
        ModelInstance previous = model;
        if (previous == fresh) {
            install();
            return;
        }
        ((RebindablePoseDriver) source).rebind(fresh);
        if (previous != null && previous.getPoseDriver() == this) {
            previous.setPoseDriver(null);
        }
        model = fresh;
        install();
    }

    /** Reinstalls this binding if another compatibility path temporarily replaced the slot. */
    public synchronized void install() {
        if (closed) {
            throw new IllegalStateException("model pose binding is closed");
        }
        ModelInstance current = model;
        if (current == null) {
            throw new IllegalStateException("model pose binding has no instance");
        }
        if (current.getPoseDriver() instanceof ModelPoseBinding previous && previous != this) {
            previous.close();
        }
        if (current.getPoseDriver() != this) {
            current.setPoseDriver(this);
        }
    }

    /**
     * Non-terminal detach used when the pipeline closes an instance. The source and this binding
     * remain usable, so resource reload can later call {@link #rebind(ModelInstance)}.
     */
    public synchronized void detach(ModelInstance instance) {
        ModelInstance current = model;
        if (closed || current != instance) {
            return;
        }
        model = null;
        if (instance.getPoseDriver() == this) {
            instance.setPoseDriver(null);
        }
    }

    /** Detaches this binding from its instance. Repeated calls have no effect. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ModelInstance current = model;
        model = null;
        if (current != null && current.getPoseDriver() == this) {
            current.setPoseDriver(null);
        }
    }
}
