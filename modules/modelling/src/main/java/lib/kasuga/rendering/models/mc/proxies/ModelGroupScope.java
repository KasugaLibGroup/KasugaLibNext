package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceResult;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Scope for several rigid {@link ModelInstance} parts belonging to one Proxy model group.
 *
 * <p>The host/FSM remains the state authority and advances the machine before calling
 * {@link #tick(float, Transform)}. This scope captures each part's immutable motion snapshot after
 * the logical tick, then publishes root and motions together as one {@link ModelGroupFrame}.
 */
public final class ModelGroupScope implements AutoCloseable {

    private static final LogicalStepPolicy POLICY =
            LogicalStepPolicy.fixed(1f / 20f, 5);

    private final ManagedAnimationScope logicalScope;
    private final ModelGroupFrameState frameState;
    private final List<RigidPartBinding> rigidTargets;
    private final @org.jetbrains.annotations.Nullable RigidPartVisualStage visualStage;
    private final Object publicationLock = new Object();
    private final ModelGroupRenderPort renderPort;
    private volatile RootSnapshot pendingRoot;
    private boolean rootAdvanced;

    ModelGroupScope(ModelGroupFrameState frameState, Transform initialRoot,
                  Collection<RigidPartBinding> targets) {
        this(frameState, initialRoot, targets, null, null);
    }

    ModelGroupScope(ModelGroupFrameState frameState, Transform initialRoot,
                    Collection<RigidPartBinding> targets,
                    @org.jetbrains.annotations.Nullable RuntimeController<Void> controller) {
        this(frameState, initialRoot, targets, controller, null);
    }

    ModelGroupScope(
            RigidPartVisualStage visualStage,
            @org.jetbrains.annotations.Nullable RuntimeController<Void> controller
    ) {
        this(
                visualStage.frameState(),
                visualStage.initialRoot(),
                visualStage.rigidTargets(),
                controller,
                visualStage);
    }

    private ModelGroupScope(
            ModelGroupFrameState frameState,
            Transform initialRoot,
            Collection<RigidPartBinding> targets,
            @org.jetbrains.annotations.Nullable RuntimeController<Void> controller,
            @org.jetbrains.annotations.Nullable RigidPartVisualStage visualStage
    ) {
        this.frameState = Objects.requireNonNull(frameState, "frameState");
        this.rigidTargets = List.copyOf(requireIndexedTargets(targets));
        this.visualStage = visualStage;
        RootSnapshot root = new RootSnapshot(initialRoot, initialRoot);
        this.pendingRoot = root;
        ManagedAnimationScope.Builder builder = ManagedAnimationScope.builder(
                UUID.randomUUID(), POLICY);
        if (controller != null) {
            builder.controller(ControllerBinding.create(controller));
        }
        for (RigidPartBinding target : rigidTargets) {
            builder.target(new TargetBinding<>(
                    target,
                    ignored -> target.evaluate(pendingRoot),
                    new TargetPublicationFactory<>()));
        }
        this.logicalScope = builder.build();
        this.renderPort = new ModelGroupRenderPort(
                logicalScope.renderPort(), frameState, publicationLock);
        if (visualStage != null) {
            visualStage.bind(renderPort);
        } else {
            for (RigidPartBinding target : rigidTargets) {
                target.attachRenderPort(renderPort);
            }
        }
        frameState.publishLogical(snapshotFrame(root));
    }

    /** Advances the shared root, then publishes one logical tick to every rigid part. */
    public void tick(float deltaSeconds, Transform currentRoot) {
        Objects.requireNonNull(currentRoot, "currentRoot");
        synchronized (publicationLock) {
            if (isClosed() || isPaused()) {
                return;
            }
            RootSnapshot next = rootAdvanced
                    ? new RootSnapshot(currentFrame().root().current(), currentRoot)
                    : new RootSnapshot(currentRoot, currentRoot);
            pendingRoot = next;
            if (visualStage != null) {
                visualStage.prepareLogicalStep(next.current());
            }
            ScopeAdvanceResult result = logicalScope.advance(new ScopeAdvance(
                    deltaSeconds, ScopeAdvanceCause.GAME_TICK));
            if (result.aborted()) {
                ProxyDebug.log("model group advance aborted: {}",
                        result.failure() == null ? "unknown failure" : result.failure());
                return;
            }
            if (result.steps().isEmpty()) {
                return;
            }
            if (visualStage != null) {
                visualStage.publishLogicalFrame();
            } else {
                frameState.publishLogical(snapshotFrame(next));
            }
            rootAdvanced = true;
        }
    }

    /** Publishes one root, then snapshots every frame-domain motion without ticking logic. */
    public void publishFrame(Transform currentRoot) {
        Objects.requireNonNull(currentRoot, "currentRoot");
        synchronized (publicationLock) {
            if (isClosed()) {
                return;
            }
            if (visualStage != null) {
                visualStage.publishFrame(currentRoot);
                rootAdvanced = true;
                return;
            }
            RootSnapshot next = new RootSnapshot(currentRoot, currentRoot);
            rootAdvanced = true;
            for (RigidPartBinding target : rigidTargets) {
                target.publishFrame();
            }
            frameState.publishFrameInput(snapshotFrame(next));
        }
    }

    ModelGroupFrame currentFrame() {
        ModelGroupFrame frame = frameState.current();
        if (frame == null) {
            throw new IllegalStateException("model group frame was not initialized");
        }
        return frame;
    }

    public boolean isPaused() {
        return logicalScope.isPaused();
    }

    public boolean isClosed() {
        return logicalScope.isClosed();
    }

    public void pause() {
        logicalScope.pause();
    }

    public void resume() {
        logicalScope.resume();
    }

    @Override
    public void close() {
        logicalScope.close();
    }

    private ModelGroupFrame snapshotFrame(RootSnapshot root) {
        List<PartMotionSnapshot> motions = new ArrayList<>(rigidTargets.size());
        for (RigidPartBinding target : rigidTargets) {
            motions.add(target.motionSnapshot());
        }
        return new ModelGroupFrame(root, motions);
    }

    private static Collection<RigidPartBinding> requireIndexedTargets(
            Collection<RigidPartBinding> targets
    ) {
        List<RigidPartBinding> ordered = new ArrayList<>(
                List.copyOf(Objects.requireNonNull(targets, "targets")));
        for (int index = 0; index < ordered.size(); index++) {
            RigidPartBinding target = ordered.get(index);
            if (target.index() != index) {
                throw new IllegalArgumentException("target at content index " + index
                        + " declares part index " + target.index());
            }
        }
        return ordered;
    }
}
