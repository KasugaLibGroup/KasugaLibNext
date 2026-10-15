package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.AnchorModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.IkModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.RagdollModule;
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
    /** Flushes the per-node pending transforms into the skeleton. Always first. */
    public static final String SLOT_APPLY = "kasuga:apply";
    /** Evaluates the hierarchy and solves PMX IK. Skipped while physics owns the pose. */
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
        this.pipeline.addLast(SLOT_PHYSICS, new RagdollModule());
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
        this.pipeline.addBefore(SLOT_IK, id, module);
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

    public void tickWithTransforms(PendingTransform[] transforms, float deltaTime) {
        validateTransforms(instance, transforms);
        // Transient IK targets live for exactly one tick: cleared here so the
        // pre-IK modules of THIS tick repopulate them and every later consumer
        // (IK solve, physics kinematic target) sees the same values.
        instance.getSkeletonInstance().clearFrameIkTargets();
        Model model = instance.getModel();
        for (var h : this.pipeline.list()) {
            h.tick(model, transforms, this, deltaTime);
        }
    }

    /** Runs every stage before the built-in physics slot for a shared scene. */
    public void tickBeforeSharedPhysics(float deltaTime) {
        clearTransientIkTargets();
        runSharedRange(0, pipeline.ids().indexOf(SLOT_PHYSICS), deltaTime);
    }

    /** Runs every stage after the built-in physics slot once the scene wrote all poses back. */
    public void tickAfterSharedPhysics(float deltaTime) {
        int physicsIndex = pipeline.ids().indexOf(SLOT_PHYSICS);
        runSharedRange(physicsIndex + 1, pipeline.size(), deltaTime);
    }

    private void clearTransientIkTargets() {
        instance.getSkeletonInstance().clearFrameIkTargets();
    }

    private void runSharedRange(int start, int end, float deltaTime) {
        List<String> ids = pipeline.ids();
        if (start < 0 || end < start || end > ids.size()) {
            throw new IllegalStateException("shared physics requires the built-in physics slot");
        }
        Model model = instance.getModel();
        for (int index = start; index < end; index++) {
            pipeline.get(ids.get(index), ModelTickLoopModule.class)
                    .tick(model, transforms, this, deltaTime);
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
        for (var h : this.pipeline.list()) {
            h.destroy(model);
        }
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
