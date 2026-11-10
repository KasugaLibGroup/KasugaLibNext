package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns the model resources and render-time placement of one rigid multi-part visual subtree.
 *
 * <p>This stage has no clock. Its logical targets are registered in the caller's single
 * {@code ProxySession} scope; this class only supplies the target bindings, binds that scope's
 * render port, retries model mounts, and snapshots frame-domain motion. Legacy
 * {@link ProxyInstance} and session-native adapters can therefore share the same part lifecycle.
 */
public final class RigidPartVisualStage implements AutoCloseable {

    @FunctionalInterface
    public interface PartHandleFactory {
        McModelHandle create(ModelPart spec, ResourceLocation instanceLoc);
    }

    private final ResourceLocation instanceBase;
    private final Supplier<@Nullable StateMachine<?>> machine;
    private final PartHandleFactory handleFactory;
    private final Vector3f worldLightOffset;
    private final List<Part> parts;
    private final ModelGroupFrameState frameState = new ModelGroupFrameState();
    private final Object publicationLock = new Object();

    private RootSnapshot evaluationRoot;
    private boolean rootAdvanced;
    private boolean suspended;
    private @Nullable ModelGroupRenderPort renderPort;
    private boolean closed;

    public RigidPartVisualStage(
            ResourceLocation instanceBase,
            List<ModelPart> specs,
            Supplier<@Nullable StateMachine<?>> machine,
            Transform initialRoot,
            Vector3f worldLightOffset,
            PartHandleFactory handleFactory
    ) {
        this.instanceBase = Objects.requireNonNull(instanceBase, "instanceBase");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.handleFactory = Objects.requireNonNull(handleFactory, "handleFactory");
        this.worldLightOffset = new Vector3f(Objects.requireNonNull(worldLightOffset, "worldLightOffset"));
        if (!this.worldLightOffset.isFinite()) {
            throw new IllegalArgumentException("worldLightOffset must be finite");
        }
        Objects.requireNonNull(initialRoot, "initialRoot");
        RootSnapshot root = new RootSnapshot(initialRoot, initialRoot);
        this.evaluationRoot = root;

        List<Part> prepared = new ArrayList<>(specs.size());
        try {
            for (int index = 0; index < specs.size(); index++) {
                ModelPart spec = Objects.requireNonNull(specs.get(index), "part");
                PartMotionSampler sampler = new PartMotionSampler(spec.motion());
                sampler.initialSnapshot(machine.get());
                RigidPartBinding target = new RigidPartBinding(
                        index, sampler, machine, frameState, spec.local(), this.worldLightOffset);
                TargetBinding<RigidPartOutput> binding = new TargetBinding<>(
                        target,
                        ignored -> target.evaluate(evaluationRoot),
                        new TargetPublicationFactory<>());
                prepared.add(new Part(spec, sampler, target, binding,
                        partInstanceKey(instanceBase, index)));
            }
            this.parts = List.copyOf(prepared);
            frameState.publishLogical(snapshotFrame(root));
        } catch (RuntimeException | Error failure) {
            for (Part part : prepared) {
                try {
                    part.target.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    public RigidPartVisualStage(
            ResourceLocation instanceBase,
            List<ModelPart> specs,
            Supplier<@Nullable StateMachine<?>> machine,
            Transform initialRoot
    ) {
        this(instanceBase, specs, machine, initialRoot, new Vector3f(),
                (part, instanceLoc) -> McModelHandle.of(
                        part.model(), part.modelName(), instanceLoc, null));
    }

    public RigidPartVisualStage(
            ResourceLocation instanceBase,
            List<ModelPart> specs,
            Supplier<@Nullable StateMachine<?>> machine,
            Transform initialRoot,
            Vector3f worldLightOffset
    ) {
        this(instanceBase, specs, machine, initialRoot, worldLightOffset,
                (part, instanceLoc) -> McModelHandle.of(
                        part.model(), part.modelName(), instanceLoc, null));
    }

    public RigidPartVisualStage(
            ResourceLocation instanceBase,
            List<ModelPart> specs,
            Supplier<@Nullable StateMachine<?>> machine,
            Transform initialRoot,
            PartHandleFactory handleFactory
    ) {
        this(instanceBase, specs, machine, initialRoot, new Vector3f(), handleFactory);
    }

    /** Registers one logical target per part in the caller-owned animation scope. */
    public List<TargetBinding<?>> targetBindings() {
        List<TargetBinding<?>> result = new ArrayList<>(parts.size());
        for (Part part : parts) {
            result.add(part.binding);
        }
        return List.copyOf(result);
    }

    /** Runtime skin that joins this stage's frame-domain snapshot to the session's target batch. */
    public void bind(AnimationRenderPort logicalRenderPort) {
        Objects.requireNonNull(logicalRenderPort, "logicalRenderPort");
        synchronized (publicationLock) {
            requireOpen();
            ModelGroupRenderPort next = new ModelGroupRenderPort(
                    logicalRenderPort, frameState, publicationLock);
            renderPort = next;
            for (Part part : parts) {
                part.target.attachRenderPort(next);
            }
        }
    }

    /**
     * Captures this logical step's root before target evaluation. Repeated calls before a scope
     * advance replace the pending root rather than manufacturing another step.
     */
    public void prepareLogicalStep(Transform currentRoot) {
        Objects.requireNonNull(currentRoot, "currentRoot");
        synchronized (publicationLock) {
            requireOpen();
            Transform previous = evaluationRoot.current();
            evaluationRoot = rootAdvanced
                    ? new RootSnapshot(previous, currentRoot)
                    : new RootSnapshot(currentRoot, currentRoot);
            rootAdvanced = true;
        }
    }

    /** Publishes the immutable root/motion generation after a logical step has committed. */
    public void publishLogicalFrame() {
        synchronized (publicationLock) {
            requireOpen();
            frameState.publishLogical(snapshotFrame(evaluationRoot));
        }
    }

    /**
     * Captures one host frame without advancing logical time or creating a revision, then emits a
     * single atomic root/motion snapshot for every attached part.
     */
    public void publishFrame(Transform currentRoot) {
        Objects.requireNonNull(currentRoot, "currentRoot");
        synchronized (publicationLock) {
            if (closed || suspended) {
                return;
            }
            RootSnapshot frameRoot = new RootSnapshot(currentRoot, currentRoot);
            for (Part part : parts) {
                part.target.publishFrame();
            }
            frameState.publishFrameInput(snapshotFrame(frameRoot));
        }
    }

    /** Creates missing handles and reattaches mounted instances after resource reloads. */
    public void maintainVisuals() {
        synchronized (publicationLock) {
            if (closed || suspended) {
                return;
            }
            publishLogicalFrame();
            try {
                for (Part part : parts) {
                    maintain(part);
                }
            } catch (RuntimeException | Error failure) {
                destroyHandles(failure);
                throw failure;
            }
        }
    }

    /** Resource recovery entry used by session adapters; false keeps the session waiting. */
    public boolean rebind() {
        synchronized (publicationLock) {
            requireOpen();
            RuntimeException failure = null;
            for (Part part : parts) {
                try {
                    retry(part);
                } catch (RuntimeException partFailure) {
                    if (failure == null) {
                        failure = partFailure;
                    } else {
                        failure.addSuppressed(partFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            return mountedPartCount() == partCount();
        }
    }

    /** Stops rendering and all visual maintenance while preserving the session and publications. */
    public void suspend() {
        synchronized (publicationLock) {
            if (closed || suspended) {
                return;
            }
            suspended = true;
            RuntimeException failure = null;
            for (Part part : parts) {
                try {
                    McModelHandle handle = part.handle;
                    if (handle != null) {
                        handle.hide();
                    }
                } catch (RuntimeException partFailure) {
                    failure = CleanupFailures.accumulate(failure, partFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Restores the same mounted model instances after a hide. */
    public void resume() {
        synchronized (publicationLock) {
            if (closed || !suspended) {
                return;
            }
            suspended = false;
            RuntimeException failure = null;
            for (Part part : parts) {
                try {
                    McModelHandle handle = part.handle;
                    if (handle != null) {
                        handle.show();
                    }
                } catch (RuntimeException partFailure) {
                    failure = CleanupFailures.accumulate(failure, partFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public int partCount() {
        return parts.size();
    }

    public int mountedPartCount() {
        int mounted = 0;
        for (Part part : parts) {
            McModelHandle handle = part.handle;
            if (handle != null && handle.isMounted()) {
                mounted++;
            }
        }
        return mounted;
    }

    @Override
    public void close() {
        synchronized (publicationLock) {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            for (Part part : parts) {
                try {
                    part.target.close();
                } catch (RuntimeException cleanupFailure) {
                    failure = CleanupFailures.accumulate(failure, cleanupFailure);
                }
                try {
                    part.destroyHandle();
                } catch (RuntimeException cleanupFailure) {
                    failure = CleanupFailures.accumulate(failure, cleanupFailure);
                }
            }
            renderPort = null;
            if (failure != null) {
                throw failure;
            }
        }
    }

    ModelGroupFrameState frameState() {
        return frameState;
    }

    List<RigidPartBinding> rigidTargets() {
        List<RigidPartBinding> result = new ArrayList<>(parts.size());
        for (Part part : parts) {
            result.add(part.target);
        }
        return List.copyOf(result);
    }

    Transform initialRoot() {
        return evaluationRoot.current();
    }

    private void maintain(Part part) {
        McModelHandle handle = part.handle;
        if (handle == null) {
            try {
                handle = Objects.requireNonNull(
                        handleFactory.create(part.spec, part.instanceLoc),
                        "part handle factory returned null");
                part.handle = handle;
                handle.setPose(initialPose(part));
                applyVisibility(handle);
            } catch (RuntimeException | Error failure) {
                if (handle != null) {
                    part.handle = null;
                    try {
                        handle.destroy();
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
        try {
            if (!handle.isMounted() && !handle.mount()) {
                return;
            }
            attach(part, handle);
        } catch (RuntimeException | Error failure) {
            part.handle = null;
            try {
                handle.destroy();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Non-destructive recovery: keep an existing handle even when this attempt fails. */
    private void retry(Part part) {
        McModelHandle handle = part.handle;
        if (handle == null) {
            McModelHandle fresh = Objects.requireNonNull(
                    handleFactory.create(part.spec, part.instanceLoc),
                    "part handle factory returned null");
            try {
                fresh.setPose(initialPose(part));
                applyVisibility(fresh);
                handle = fresh;
                part.handle = fresh;
            } catch (RuntimeException | Error failure) {
                try {
                    fresh.destroy();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
        if (!handle.isMounted() && !handle.mount()) {
            return;
        }
        attach(part, handle);
    }

    private void destroyHandles(@Nullable Throwable primary) {
        for (Part part : parts) {
            try {
                part.destroyHandle();
            } catch (RuntimeException cleanupFailure) {
                if (primary == null) {
                    primary = cleanupFailure;
                } else {
                    primary.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    private void attach(Part part, McModelHandle handle) {
        ModelInstance instance = handle.instance();
        if (instance == null) {
            return;
        }
        part.target.attachTo(instance);
    }

    private void applyVisibility(McModelHandle handle) {
        if (suspended) {
            handle.hide();
        } else {
            handle.show();
        }
    }

    private Transform initialPose(Part part) {
        Transform pose = evaluationRoot.current().mul(part.spec.local());
        Transform motion = part.sampler.snapshot().sample(1f);
        if (motion != null) {
            pose.mul(motion);
        }
        return pose;
    }

    private ModelGroupFrame snapshotFrame(RootSnapshot root) {
        List<PartMotionSnapshot> motions = new ArrayList<>(parts.size());
        for (Part part : parts) {
            motions.add(part.sampler.snapshot());
        }
        return new ModelGroupFrame(root, motions);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("rigid part visual stage is closed");
        }
    }

    static ResourceLocation partInstanceKey(ResourceLocation proxyKey, int index) {
        return ResourceLocation.fromNamespaceAndPath(
                proxyKey.getNamespace(), proxyKey.getPath() + "/part/" + index);
    }

    private static final class Part {
        private final ModelPart spec;
        private final PartMotionSampler sampler;
        private final RigidPartBinding target;
        private final TargetBinding<RigidPartOutput> binding;
        private final ResourceLocation instanceLoc;
        private @Nullable McModelHandle handle;

        private Part(
                ModelPart spec,
                PartMotionSampler sampler,
                RigidPartBinding target,
                TargetBinding<RigidPartOutput> binding,
                ResourceLocation instanceLoc
        ) {
            this.spec = spec;
            this.sampler = sampler;
            this.target = target;
            this.binding = binding;
            this.instanceLoc = instanceLoc;
        }

        private void destroyHandle() {
            McModelHandle current = handle;
            handle = null;
            if (current != null) {
                current.destroy();
            }
        }
    }
}
