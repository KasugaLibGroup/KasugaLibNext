package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.AnchorModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.IkModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.SkeletonPhysicsModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.SkeletonApplyModule;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The single ordered evaluation pipeline of one {@link ModelInstance}.
 *
 * <p>Every procedural stage — animation post-processing, IK, physics, anchor
 * tracking — is a {@link ModelTickLoopModule} mounted into this pipeline. The
 * framework owns the ordering through well-known slots; modules only implement
 * their own internal logic and never schedule each other.</p>
 *
 * <p>Canonical order:</p>
 * <pre>{@code
 * [user pre-IK modules] -> kasuga:apply -> kasuga:ik -> [user post-IK modules]
 *   -> kasuga:physics -> [user post-physics modules] -> kasuga:anchor
 * }</pre>
 */
public class ModelTickLoop {
    /** Flushes per-node pending transforms after user input modules. */
    public static final String SLOT_APPLY = "kasuga:apply";
    /** Evaluates the animation hierarchy and constrained IK target for every motion state. */
    public static final String SLOT_IK = "kasuga:ik";
    /** Advances the attached ragdoll (Box3D) and writes the result back. No-op without physics. */
    public static final String SLOT_PHYSICS = "kasuga:physics";
    /** Recomputes display-anchor world transforms after the final pose is known. Always last. */
    public static final String SLOT_ANCHOR = "kasuga:anchor";

    private static final int BUILTIN_MODULE_COUNT = 4;

    @Getter
    private final TickLoopPipeline<ModelTickLoopModule> pipeline = new TickLoopPipeline<>();
    @Getter
    private final ModelInstance instance;
    /**
     * Per-node transforms passed through the tick pipeline. Slot {@code 0} is
     * the model/skeleton root transform; slot {@code i + 1} belongs to
     * {@code Skeleton.getBones()[i]}.
     */
    @Getter
    private final PendingTransform[] transforms;

    /** Set by the typed helpers; direct {@link #getPipeline()} mutations are detected via size. */
    private boolean proceduralModulesAttached;
    private boolean evaluating;
    private boolean sharedPhysics;

    public ModelTickLoop(ModelInstance instance) {
        this(instance, createTransforms(instance));
    }

    public ModelTickLoop(ModelInstance instance, PendingTransform[] transforms) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.transforms = validateTransforms(instance, transforms);
        installDefaults();
    }

    private void installDefaults() {
        this.pipeline.addLast(SLOT_APPLY, new SkeletonApplyModule());
        this.pipeline.addLast(SLOT_IK, new IkModule());
        this.pipeline.addLast(SLOT_PHYSICS, new SkeletonPhysicsModule());
        this.pipeline.addLast(SLOT_ANCHOR, new AnchorModule());
    }

    public Model getModel() {
        return instance.getModel();
    }

    /**
     * Registers a module that runs before the IK stage — the place to write
     * pending transforms or transient IK targets for this tick.
     */
    public void addPreIk(String id, ModelTickLoopModule module) {
        this.proceduralModulesAttached = true;
        this.pipeline.addBefore(SLOT_APPLY, id, module);
    }

    /**
     * Registers a module that runs after IK but before physics — the place to
     * read the solved pose or drive controllers such as active ragdolls.
     */
    public void addPostIk(String id, ModelTickLoopModule module) {
        this.proceduralModulesAttached = true;
        this.pipeline.addAfter(SLOT_IK, id, module);
    }

    /**
     * Registers a module that runs after physics wrote its pose back — the
     * place to observe or amend the final pose before anchors are recomputed.
     */
    public void addPostPhysics(String id, ModelTickLoopModule module) {
        this.proceduralModulesAttached = true;
        this.pipeline.addBefore(SLOT_ANCHOR, id, module);
    }

    /** Returns the built-in or user module registered under a slot/id. */
    public <M extends ModelTickLoopModule> M module(String id, Class<M> type) {
        return this.pipeline.get(id, type);
    }

    /**
     * True when any user module is mounted, so consumers (such as
     * {@code ModelInstance.update()}) know the loop must run even without a
     * dirty skeleton. Removing built-ins via {@code getPipeline()} bypasses
     * this flag on purpose — whoever removes them owns their responsibilities.
     */
    public boolean hasProceduralModules() {
        return this.proceduralModulesAttached || this.pipeline.size() != BUILTIN_MODULE_COUNT;
    }

    public void tick(float deltaTime) {
        this.tickWithTransforms(this.transforms, deltaTime);
    }

    public boolean isEvaluating() { return evaluating; }
    public boolean isSharedPhysics() { return sharedPhysics; }

    public void beforePhysicsStep() {
        if (!evaluating) throw new IllegalStateException("fixed steps require an evaluating model pipeline");
        for (var module : pipeline.list()) module.beforePhysicsStep(getModel(), this);
    }

    public void tickWithTransforms(PendingTransform[] transforms, float deltaTime) {
        validateTransforms(instance, transforms);
        var relaxation = instance.getSkeletonInstance().getRelaxation();
        if (relaxation != null && relaxation.enabled()) {
            if (!(deltaTime > 0f) || !Float.isFinite(deltaTime)) return;
            if (relaxation.physicsScene() != null) {
                throw new IllegalStateException("shared skeletons must be advanced through their scene");
            }
        }
        beginEvaluation();
        try {
            Model model = instance.getModel();
            for (var module : pipeline.list()) module.tick(model, transforms, this, deltaTime);
        } finally {
            evaluating = false;
        }
    }

    /** Compatibility/reset entry: evaluate only the standard input/apply/IK stages. */
    public void evaluateTarget() {
        beginEvaluation();
        try {
            int ik = requiredSlot(SLOT_IK);
            runRange(0, ik + 1, 0f);
        } finally {
            evaluating = false;
        }
    }

    private void beginEvaluation() {
        instance.requireOpen();
        if (evaluating) throw new IllegalStateException("model pipeline cannot evaluate recursively");
        evaluating = true;
        try {
            instance.getSkeletonInstance().beginPoseEvaluation();
        } catch (RuntimeException | Error failure) {
            evaluating = false;
            throw failure;
        }
    }

    /**
     * Standard cross-model physics barrier. Every participant executes its physics slot to submit
     * targets/controllers, then the world advances once, then all poses commit before observers run.
     * The caller supplies the simulation service; scenes never schedule individual model stages.
     */
    public static void tickSharedPhysics(List<ModelTickLoop> participants, float deltaTime, Runnable solveWorld) {
        Objects.requireNonNull(solveWorld, "solveWorld");
        if (!(deltaTime > 0f) || !Float.isFinite(deltaTime)) return;
        List<ModelTickLoop> loops = List.copyOf(participants);
        var unique = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ModelTickLoop, Boolean>());
        for (ModelTickLoop loop : loops) {
            if (!unique.add(loop)) throw new IllegalArgumentException("duplicate model in physics batch");
            loop.requiredSlot(SLOT_PHYSICS);
            if (loop.evaluating) throw new IllegalStateException("model pipeline cannot evaluate recursively");
        }
        java.util.ArrayList<ModelTickLoop> started = new java.util.ArrayList<>();
        try {
            for (ModelTickLoop loop : loops) {
                loop.sharedPhysics = true;
                started.add(loop);
                loop.beginEvaluation();
            }
            for (ModelTickLoop loop : loops) {
                loop.runRange(0, loop.requiredSlot(SLOT_PHYSICS) + 1, deltaTime);
            }
            solveWorld.run();
            for (ModelTickLoop loop : loops) {
                loop.pipeline.get(SLOT_PHYSICS, ModelTickLoopModule.class)
                        .afterSharedPhysics(loop.getModel(), loop.transforms, loop, deltaTime);
            }
            for (ModelTickLoop loop : loops) loop.tickAfterSharedPhysics(deltaTime);
        } finally {
            for (ModelTickLoop loop : started) {
                loop.evaluating = false;
                loop.sharedPhysics = false;
            }
        }
    }

    /** Legacy range helper; new shared worlds should use {@link #tickSharedPhysics}. */
    public void tickBeforeSharedPhysics(float deltaTime) {
        instance.getSkeletonInstance().beginPoseEvaluation();
        runRange(0, requiredSlot(SLOT_PHYSICS), deltaTime);
    }

    public void tickAfterSharedPhysics(float deltaTime) {
        runRange(requiredSlot(SLOT_PHYSICS) + 1, pipeline.size(), deltaTime);
    }

    private int requiredSlot(String slot) {
        int index = pipeline.ids().indexOf(slot);
        if (index < 0) throw new IllegalStateException("standard pipeline requires slot " + slot);
        return index;
    }

    private void runRange(int start, int end, float deltaTime) {
        List<ModelTickLoopModule> modules = pipeline.list();
        for (int index = start; index < end; index++) {
            modules.get(index).tick(getModel(), transforms, this, deltaTime);
        }
    }

    public PendingTransform rootTransform() {
        return transforms[0];
    }

    public PendingTransform boneTransform(int boneIndex) {
        Bone[] bones = instance.getModel().getSkeleton().getBones();
        if (boneIndex < 0 || boneIndex >= bones.length) {
            throw new IndexOutOfBoundsException("boneIndex=" + boneIndex + ", boneCount=" + bones.length);
        }
        return transforms[boneIndex + 1];
    }

    public PendingTransform boneTransform(Bone bone) {
        Objects.requireNonNull(bone, "bone");
        Bone[] bones = instance.getModel().getSkeleton().getBones();
        int index = bone.getIndex();
        if (index < 0 || index >= bones.length || bones[index] != bone) {
            throw new IllegalArgumentException("bone does not belong to this model skeleton: " + bone.getName());
        }
        return transforms[index + 1];
    }

    public void destroy() {
        Model model = instance.getModel();
        RuntimeException failure = null;
        for (var h : this.pipeline.list()) {
            try { h.destroy(model); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    private static PendingTransform[] createTransforms(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance");
        int size = instance.getModel().getSkeleton().getBones().length + 1;
        PendingTransform[] transforms = new PendingTransform[size];
        Arrays.setAll(transforms, ignored -> new PendingTransform());
        return transforms;
    }

    private static PendingTransform[] validateTransforms(ModelInstance instance, PendingTransform[] transforms) {
        Objects.requireNonNull(transforms, "transforms");
        int expected = instance.getModel().getSkeleton().getBones().length + 1;
        if (transforms.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " pending transforms (root + " + (expected - 1)
                            + " bones), got " + transforms.length);
        }
        for (int i = 0; i < transforms.length; i++) {
            if (transforms[i] == null) {
                throw new IllegalArgumentException("pending transform slot " + i + " is null");
            }
        }
        return transforms;
    }
}
