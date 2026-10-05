package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.framework.render.ModelGeometryAdapter;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonInstanceData;
import lib.kasuga.rendering.models.uml.dynamic.ik.CalikoIkSolver;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.IkChain;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.TargetMode;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.ParentBoneInherit;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.*;

@Getter
public class SkeletonInstance {

    private final ModelInstance modelInstance;
    private final Skeleton skeleton;
    private final Bone[] pmxBones;

    private final HashMap<Bone, Transform> transforms;
    private final HashMap<Bone, Transform> absoluteTransforms;
    private final HashMap<Bone, Transform> evaluatedTransforms;
    private final HashMap<Bone, Transform> ikTransforms;
    private final HashMap<Bone, Transform> physicsTransforms;
    private final HashMap<String, Boolean> ikEnabled;
    private final HashMap<String, IkTarget> ikTargets;
    private final HashMap<String, IkTarget> frameIkTargets;
    private final Set<Bone> dirtyBones;
    private Set<Bone> lastDirtyBones;

    /** BFS work queue; bones only — parent absolutes are read from {@link #absoluteTransforms}. */
    private final ArrayDeque<Bone> updateQueue = new ArrayDeque<>();
    /** Scratch for parent∘bind composition; single-threaded per instance. */
    private final Transform composeScratch = new Transform();
    /** Shared read-only identity for bones without an authored local transform. */
    private static final Transform IDENTITY_TRANSFORM = new Transform();

    // Preallocated scratch for grant/fixed-axis/IK math — these run per bone
    // per evaluation, so per-call allocation would dominate frame cost.
    private final Vector3f grantPositionScratch = new Vector3f();
    private final Quaternionf grantSlerpScratch = new Quaternionf();
    private final Vector3f fixedAxisDirScratch = new Vector3f();
    private final Quaternionf fixedAxisRotScratch = new Quaternionf();
    private final Quaternionf fixedAxisTwistScratch = new Quaternionf();
    private final Vector3f fixedAxisVecScratch = new Vector3f();
    private final Vector3f fixedAxisPosScratch = new Vector3f();
    private final Vector3f fixedAxisScaleScratch = new Vector3f();
    private final Matrix4f fixedAxisMatrixScratch = new Matrix4f();
    private final Matrix4f anchorDeltaScratch = new Matrix4f();
    private final Matrix4f anchorBlendScratch = new Matrix4f();
    private final CalikoIkSolver ikSolver;

    /**
     * Bumped by every pose-input mutation (bone locals, root, IK targets,
     * physics writeback). Lets physics skip redundant kinematic re-evaluation
     * without tracking individual mutators.
     */
    @Getter
    private long mutationEpoch;

    @NonNull
    private Transform transform;

    /**
     * High-precision world anchor used when bone matrices are evaluated in a
     * nearby local coordinate system. Keeping this separate from the float
     * root matrix prevents large Minecraft coordinates from quantizing short
     * bones before skinning or physics sees them.
     */
    private final Vector3d worldOrigin = new Vector3d();
    private boolean floatingOriginEnabled;

    private SkeletonInstanceData data;

    private boolean shouldUpdate;
    private boolean fullUpdateRequested;
    private boolean lastFullUpdate;
    private long version;

    public SkeletonInstance(ModelInstance instance, Skeleton skeleton, @Nullable Transform transform, @Nullable SkeletonInstanceData data) {
        this.modelInstance = instance;
        this.skeleton = skeleton;
        this.pmxBones = Arrays.stream(skeleton.getBones())
                .filter(bone -> bone.getBoneData() instanceof PmxBone)
                .toArray(Bone[]::new);
        this.transform = transform != null ? transform : new Transform();
        shouldUpdate = false;
        this.transforms = new HashMap<>();
        this.absoluteTransforms = new HashMap<>();
        this.evaluatedTransforms = new HashMap<>();
        this.ikTransforms = new HashMap<>();
        this.physicsTransforms = new HashMap<>();
        this.ikEnabled = new HashMap<>();
        this.ikTargets = new HashMap<>();
        this.frameIkTargets = new HashMap<>();
        this.dirtyBones = new HashSet<>();
        this.lastDirtyBones = Collections.emptySet();
        this.data = data;
        this.fullUpdateRequested = true;
        this.lastFullUpdate = true;
        this.version = 0;
        this.ikSolver = new CalikoIkSolver(skeleton, new CalikoIkSolver.PoseAccess() {
            public Transform absolute(Bone bone) { return absoluteTransforms.get(bone); }
            public Transform correction(Bone bone) {
                return ikTransforms.computeIfAbsent(bone, ignored -> new Transform());
            }
            public Vector3f target(IkChain chain) { return ikTargetPosition(chain); }
            public boolean enabled(String name) { return isIkEnabled(name); }
            public void refresh(Bone driver, Bone endpoint) { evaluateHierarchyFrom(driver, endpoint); }
        });
        updateTransform();
    }

    public void getMorphTransform(Bone original, Transform dest) {
        Transform t = modelInstance.getMorph().getCachedTransform(original);
        if (t == null) return;
        dest.mul(t);
    }

    public void updateTransform() {
        updateTransform(true);
    }

    /**
     * Rebuilds the final hierarchy after physics has replaced some local bone
     * transforms. The IK corrections were already solved while evaluating the
     * animation target, so solving them again here is both redundant and can
     * fight the physical pose.
     */
    public void updateTransformAfterPhysics() {
        updateTransform(false);
    }

    private void updateTransform(boolean solveIk) {
        Set<Bone> updatedBones = collectUpdatedBones();
        if (solveIk) ikTransforms.clear();
        evaluateHierarchy();
        if (solveIk) {
            ikSolver.solve();
            if (!skeleton.getDynamics().ikChains().isEmpty()) evaluateHierarchy();
        }
        lastFullUpdate = fullUpdateRequested || updatedBones.isEmpty()
                || solveIk && !skeleton.getDynamics().ikChains().isEmpty();
        lastDirtyBones = lastFullUpdate ? Collections.emptySet() : updatedBones;
        dirtyBones.clear();
        fullUpdateRequested = false;
        shouldUpdate = false;
        version++;
    }

    public boolean checkShouldUpdate() {
        this.shouldUpdate = shouldUpdate || isMorphUpdated();
        return this.shouldUpdate;
    }

    public void setShouldUpdate(boolean shouldUpdate) {
        this.shouldUpdate = shouldUpdate;
        if (shouldUpdate) {
            requestFullUpdate();
        }
    }

    public boolean transform(String boneName, Transform transform) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        transforms.put(bone, transform);
        markDirty(bone);
        return true;
    }

    public boolean transform(Bone bone, Transform transform) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        transforms.put(bone, transform);
        markDirty(bone);
        return true;
    }

    public boolean mulTransform(String boneName, Transform transform) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(transform);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean mulTransform(Bone bone, Transform transform) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(transform);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean offset(String boneName, Vector3f offset) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.translate(offset);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean offset(Bone bone, Vector3f offset) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.translate(offset);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean rotate(String boneName, Quaternionf rotation) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(rotation);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean rotate(Bone bone, Quaternionf rotation) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(rotation);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean scale(String boneName, Vector3f scale) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.scale(scale.x(), scale.y(), scale.z());
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean scale(Bone bone, Vector3f scale) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.scale(scale.x(), scale.y(), scale.z());
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean reset(String boneName) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        transforms.remove(bone);
        markDirty(bone);
        return true;
    }

    public boolean reset(Bone bone) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        transforms.remove(bone);
        markDirty(bone);
        return true;
    }

    public boolean resetAll() {
        if (transforms.isEmpty()) return false;
        transforms.clear();
        requestFullUpdate();
        return true;
    }

    public void transformRoot(@NonNull Transform transform) {
        this.transform = transform;
        requestFullUpdate();
    }

    /**
     * Moves the current float root translation into a double world anchor and
     * immediately rebuilds the hierarchy around the local origin.
     */
    public void enableFloatingOrigin() {
        if (floatingOriginEnabled) return;
        Vector3f rootPosition = transform.getPosition();
        worldOrigin.set(rootPosition.x, rootPosition.y, rootPosition.z);
        transform = transform.copy().setPosition(new Vector3f());
        floatingOriginEnabled = true;
        requestFullUpdate();
        updateTransform();
    }

    /**
     * Enables local-coordinate evaluation with an exact external world
     * anchor. The current root transform is treated as origin-relative.
     */
    public void enableFloatingOrigin(@NonNull Vector3dc origin) {
        if (floatingOriginEnabled) {
            if (!worldOrigin.equals(origin, 0.0)) {
                throw new IllegalStateException("floating origin is already enabled");
            }
            return;
        }
        if (!Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z())) {
            throw new IllegalArgumentException("floating origin must be finite");
        }
        worldOrigin.set(origin);
        floatingOriginEnabled = true;
        requestFullUpdate();
        updateTransform();
    }

    /**
     * Changes the high-precision anchor while preserving the root's world
     * position. Used when several models join one shared physics scene.
     */
    public void rebaseFloatingOrigin(@NonNull Vector3dc origin) {
        if (!Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z())) {
            throw new IllegalArgumentException("floating origin must be finite");
        }
        Vector3f localRoot = transform.getPosition();
        double worldX = (floatingOriginEnabled ? worldOrigin.x : 0.0) + localRoot.x;
        double worldY = (floatingOriginEnabled ? worldOrigin.y : 0.0) + localRoot.y;
        double worldZ = (floatingOriginEnabled ? worldOrigin.z : 0.0) + localRoot.z;
        transform = transform.copy().setPosition(new Vector3f(
                (float) (worldX - origin.x()),
                (float) (worldY - origin.y()),
                (float) (worldZ - origin.z())));
        worldOrigin.set(origin);
        floatingOriginEnabled = true;
        requestFullUpdate();
        updateTransform();
    }

    /** Returns a defensive copy of the high-precision world anchor. */
    public Vector3d getWorldOrigin() {
        return new Vector3d(worldOrigin);
    }

    /** Exact world-space position of the skeleton root. */
    public Vector3d getWorldRootPosition() {
        Vector3f local = transform.getPosition();
        return new Vector3d(floatingOriginEnabled ? worldOrigin : new Vector3d())
                .add(local.x, local.y, local.z);
    }

    /**
     * Returns the root transform using the legacy world-space translation.
     * Rotation and scale are unchanged; callers that require exact translation
     * should pair this with {@link #getWorldRootPosition()}.
     */
    public Transform getWorldTransform() {
        Vector3d position = getWorldRootPosition();
        return transform.copy().setPosition(new Vector3f(
                (float) position.x, (float) position.y, (float) position.z));
    }

    /** Replaces the root using a world-space translation. */
    public void transformRootWorld(@NonNull Transform worldTransform) {
        Transform local = worldTransform.copy();
        if (floatingOriginEnabled) {
            Vector3f position = worldTransform.getPosition();
            local.setPosition(new Vector3f(
                    (float) (position.x - worldOrigin.x),
                    (float) (position.y - worldOrigin.y),
                    (float) (position.z - worldOrigin.z)));
        }
        transformRoot(local);
    }

    /** Changes only the root's exact world-space position. */
    public void setWorldRootPosition(@NonNull Vector3dc position) {
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z())) {
            throw new IllegalArgumentException("root position must be finite");
        }
        transform = transform.copy().setPosition(new Vector3f(
                (float) (position.x() - (floatingOriginEnabled ? worldOrigin.x : 0.0)),
                (float) (position.y() - (floatingOriginEnabled ? worldOrigin.y : 0.0)),
                (float) (position.z() - (floatingOriginEnabled ? worldOrigin.z : 0.0))));
        requestFullUpdate();
    }

    public Vector3f worldToLocal(double x, double y, double z) {
        return new Vector3f((float) (x - worldOrigin.x),
                (float) (y - worldOrigin.y), (float) (z - worldOrigin.z));
    }

    public Vector3d localToWorld(Vector3f local) {
        Objects.requireNonNull(local, "local");
        return new Vector3d(worldOrigin).add(local.x, local.y, local.z);
    }

    public void mulTransformRoot(@NonNull Transform transform) {
        this.transform.mul(transform);
        requestFullUpdate();
    }

    public void offsetRoot(@NonNull Vector3f offset) {
        this.transform.translate(offset);
        requestFullUpdate();
    }

    public void rotateRoot(@NonNull Quaternionf rotation) {
        this.transform.mul(rotation);
        requestFullUpdate();
    }

    public void scaleRoot(@NonNull Vector3f scale) {
        this.transform.scale(scale.x(), scale.y(), scale.z());
        requestFullUpdate();
    }

    public void resetRoot() {
        this.transform = new Transform();
        requestFullUpdate();
    }

    public void tick() {
        if (shouldUpdate) {
            updateTransform();
            shouldUpdate = false;
        }
    }

    public boolean isBindPose() {
        return transforms.isEmpty() && transform.isIdentity();
    }

    public boolean setIkEnabled(String boneName, boolean enabled) {
        if (ikController(boneName) == null) return false;
        ikEnabled.put(boneName, enabled);
        requestFullUpdate();
        return true;
    }

    public void resetIkEnabled() {
        if (ikEnabled.isEmpty()) return;
        ikEnabled.clear();
        requestFullUpdate();
    }

    public boolean isIkEnabled(String boneName) {
        return ikEnabled.getOrDefault(boneName, true);
    }

    /** Sets a persistent world-space target for a named IK chain. */
    public boolean setIkTarget(String controllerBone, Vector3f worldTarget, float weight) {
        if (ikController(controllerBone) == null) return false;
        ikTargets.put(controllerBone, ikTarget(worldTarget, weight));
        requestFullUpdate();
        return true;
    }

    public boolean clearIkTarget(String controllerBone) {
        if (ikTargets.remove(controllerBone) == null) return false;
        requestFullUpdate();
        return true;
    }

    public void clearIkTargets() {
        if (ikTargets.isEmpty()) return;
        ikTargets.clear();
        requestFullUpdate();
    }

    /** Sets an IK target valid only for the current pose-pipeline evaluation. */
    public boolean setFrameIkTarget(String controllerBone, Vector3f worldTarget, float weight) {
        if (ikController(controllerBone) == null) return false;
        frameIkTargets.put(controllerBone, ikTarget(worldTarget, weight));
        requestFullUpdate();
        return true;
    }

    /** Called by the model pose pipeline before its BEFORE_IK effectors. */
    public void clearFrameIkTargets() {
        if (frameIkTargets.isEmpty()) return;
        frameIkTargets.clear();
        requestFullUpdate();
    }

    private IkChain ikController(String name) {
        return skeleton.getDynamics().chainsByName().get(name);
    }

    /** Diagnostics distinguish an unreachable/limited target from a satisfied solve. */
    public Map<String, CalikoIkSolver.SolveResult> ikSolveResults() { return ikSolver.results(); }

    private Vector3f ikTargetPosition(IkChain chain) {
        Vector3f target = absoluteTransforms.get(chain.controller()).getPosition();
        IkTarget override = frameIkTargets.getOrDefault(chain.name(), ikTargets.get(chain.name()));
        if (chain.targetMode() == TargetMode.DIRECTION && override == null) {
            Bone base = chain.links().getFirst().bone();
            Vector3f direction = skeleton.getBindingAbsolute(chain.effector()).getPosition()
                    .sub(skeleton.getBindingAbsolute(base).getPosition());
            Quaternionf rotation = absoluteTransforms.get(chain.controller()).getRotation()
                    .mul(skeleton.getBindingAbsolute(chain.controller()).getRotation().invert());
            rotation.transform(direction);
            float length = absoluteTransforms.get(chain.effector()).getPosition()
                    .distance(absoluteTransforms.get(base).getPosition());
            if (direction.lengthSquared() > 1e-12f) direction.normalize().mul(length);
            return absoluteTransforms.get(base).getPosition().add(direction);
        }
        if (override != null) {
            Vector3f local = new Vector3f(override.position);
            if (floatingOriginEnabled) local.set((float) ((double) local.x - worldOrigin.x),
                    (float) ((double) local.y - worldOrigin.y), (float) ((double) local.z - worldOrigin.z));
            target.lerp(local, override.weight);
        }
        return target;
    }

    private static IkTarget ikTarget(Vector3f target, float weight) {
        Vector3f position = new Vector3f(Objects.requireNonNull(target, "target"));
        if (!position.isFinite() || !Float.isFinite(weight) || weight < 0f || weight > 1f) {
            throw new IllegalArgumentException("IK target must be finite and weight within [0, 1]");
        }
        return new IkTarget(position, weight);
    }

    private void evaluateHierarchy() {
        updateQueue.clear();
        Bone rootBone = skeleton.getRoot();
        Transform rootAbsolute = reusableAbsolute(rootBone);
        rootAbsolute.set(transform).mul(rootBone.getTransform());
        getMorphTransform(rootBone, rootAbsolute);
        rootAbsolute.mul(evaluatedLocalTransform(rootBone));
        updateQueue.add(rootBone);
        recursiveUpdate();
    }

    private void recursiveUpdate() {
        while (!updateQueue.isEmpty()) {
            Bone bone = updateQueue.poll();
            Transform parentTransform = absoluteTransforms.get(bone);
            Bone[] children = bone.getChildren();
            if (parentTransform == null || children == null) continue;
            for (Bone child : children) {
                if (child == null) continue;
                Transform childAbsolute = reusableAbsolute(child);
                // absolute = parent ∘ bind ∘ morph ∘ evaluated-local, all in
                // preallocated storage: zero steady-state allocation per bone.
                composeScratch.set(parentTransform);
                childAbsolute.set(child.getTransform());
                getMorphTransform(child, childAbsolute);
                composeScratch.mul(childAbsolute);
                childAbsolute.set(composeScratch).mul(evaluatedLocalTransform(child));
                updateQueue.add(child);
            }
        }
    }

    /** Returns the map-stored absolute instance for a bone, allocating on first use only. */
    private Transform reusableAbsolute(Bone bone) {
        Transform existing = absoluteTransforms.get(bone);
        if (existing == null) {
            existing = new Transform();
            absoluteTransforms.put(bone, existing);
        }
        return existing;
    }

    /** Returns the map-stored evaluated-local instance for a bone, allocating on first use only. */
    private Transform reusableEvaluated(Bone bone) {
        Transform existing = evaluatedTransforms.get(bone);
        if (existing == null) {
            existing = new Transform();
            evaluatedTransforms.put(bone, existing);
        }
        return existing;
    }

    /**
     * Composes the local pose of a bone (authored local + grant/fixed-axis +
     * IK correction + physics override) into its stored evaluated slot.
     * Callers must treat the returned instance as read-only — it aliases the
     * {@link #evaluatedTransforms} entry.
     */
    private Transform evaluatedLocalTransform(Bone bone) {
        Transform result = reusableEvaluated(bone);
        Transform authored = transforms.get(bone);
        // MMD: 启用 IK 的链上骨由解算器接管 —— 忽略动画直接旋转（VMD 大腿关键帧等），
        // 否则"大腿直接旋转 + IK 平移"的双驱动会把腿拧成怪异的姿态。
        result.set(isIkDriven(bone) ? IDENTITY_TRANSFORM
                : (authored == null ? IDENTITY_TRANSFORM : authored));
        if (bone.getBoneData() instanceof PmxBone pmx) {
            applyGrant(result, pmx);
            applyFixedAxis(result, pmx);
        }
        Transform ik = ikTransforms.get(bone);
        if (ik != null) result.mul(ik);
        Transform physics = physicsTransforms.get(bone);
        if (physics != null) result.set(physics);
        return result;
    }

    /**
     * Whether this bone is a link of at least one IK controller whose IK is
     * currently enabled. Chain membership comes from the shared reverse mapping; the
     * enable check is a per-frame map lookup (IK enable defaults to true, per
     * MMD). Chains may opt into this authored-pose replacement behavior.
     */
    private boolean isIkDriven(Bone bone) {
        for (IkChain chain : skeleton.getDynamics().ikChainsByBone().getOrDefault(bone, List.of())) {
            if (chain.replaceAuthoredRotation() && isIkEnabled(chain.name())) return true;
        }
        return false;
    }

    /**
     * Removes the last simulated pose so animation and IK can be evaluated as
     * the kinematic target for the next physics step.
     */
    public void clearPhysicsTransforms() {
        if (physicsTransforms.isEmpty()) return;
        physicsTransforms.clear();
        requestFullUpdate();
    }

    /**
     * Computes the current world transform of a skeleton anchor, following the
     * same linear-blend rule as skinned vertices: the anchor's authored
     * transform is deformed by the weighted bind-to-current deltas of its
     * bound bones. Returns {@code null} when no anchor of that name exists or
     * none of its bones have been evaluated yet.
     */
    @Nullable
    public Transform anchorTransform(String anchorName) {
        Anchor anchor = skeleton.getAnchor(anchorName);
        if (anchor == null) return null;
        Pair<Bone, Float>[] weights = anchor.getBinding().getWeights();
        // Weighted matrix blend accumulated component-wise — no per-bone
        // matrix allocations on the hot path.
        float m00 = 0f, m01 = 0f, m02 = 0f, m03 = 0f;
        float m10 = 0f, m11 = 0f, m12 = 0f, m13 = 0f;
        float m20 = 0f, m21 = 0f, m22 = 0f, m23 = 0f;
        float m30 = 0f, m31 = 0f, m32 = 0f, m33 = 0f;
        boolean blendedAnyBone = false;
        for (Pair<Bone, Float> weight : weights) {
            Bone bone = weight.getFirst();
            Transform absolute = absoluteTransforms.get(bone);
            Pair<Transform, Transform> binding = skeleton.getBoneTransforms().get(bone);
            if (absolute == null || binding == null) continue;
            // bind^-1 * current == deformation from bind pose to the evaluated pose.
            Matrix4f delta = anchorDeltaScratch.set(binding.getSecond().transform())
                    .mul(absolute.transform());
            float w = weight.getSecond();
            m00 += w * delta.m00(); m01 += w * delta.m01(); m02 += w * delta.m02(); m03 += w * delta.m03();
            m10 += w * delta.m10(); m11 += w * delta.m11(); m12 += w * delta.m12(); m13 += w * delta.m13();
            m20 += w * delta.m20(); m21 += w * delta.m21(); m22 += w * delta.m22(); m23 += w * delta.m23();
            m30 += w * delta.m30(); m31 += w * delta.m31(); m32 += w * delta.m32(); m33 += w * delta.m33();
            blendedAnyBone = true;
        }
        if (!blendedAnyBone) return null;
        Matrix4f blended = anchorBlendScratch.set(m00, m01, m02, m03,
                m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
        blended.mul(anchor.getTransform().transform());
        if (floatingOriginEnabled) {
            // The legacy attachment API returns a float world transform. Keep
            // it spatially compatible; precision-sensitive consumers should
            // pair origin-local bone data with getWorldOrigin() instead.
            blended.setTranslation(
                    blended.m30() + (float) worldOrigin.x,
                    blended.m31() + (float) worldOrigin.y,
                    blended.m32() + (float) worldOrigin.z);
        }
        return new Transform().set(blended);
    }

    /** Applies a complete set of physics-produced local bone transforms. */
    public void applyPhysicsTransforms(Map<Bone, Transform> pose) {
        physicsTransforms.clear();
        pose.forEach((bone, value) -> {
            if (skeleton.getBoneTransforms().containsKey(bone)) {
                physicsTransforms.put(bone, value.copy());
            }
        });
        requestFullUpdate();
    }

    private void applyGrant(Transform result, PmxBone pmx) {
        ParentBoneInherit inherit = pmx.inherit;
        if (inherit == null) return;
        Bone source = pmxBone(inherit.parentIndex().intValue());
        if (source == null) return;
        Transform sourceTransform = evaluatedTransforms.getOrDefault(source,
                transforms.getOrDefault(source, IDENTITY_TRANSFORM));
        float weight = inherit.weight();
        if (pmx.flags.inheritParentTranslation) {
            Vector3f position = grantPositionScratch.set(sourceTransform.transform().m30(),
                    sourceTransform.transform().m31(), sourceTransform.transform().m32());
            result.translateWorld(position.mul(weight));
        }
        if (pmx.flags.inheritParentRotation) {
            result.mul(grantSlerpScratch.identity().slerp(sourceTransform.getRotation(), weight));
        }
    }

    private void applyFixedAxis(Transform result, PmxBone pmx) {
        if (!pmx.flags.isAxisFixed || pmx.fixedAxis == null || pmx.fixedAxis.lengthSquared() < 1e-8f) return;
        Vector3f axis = fixedAxisDirScratch.set(pmx.fixedAxis).normalize();
        Quaternionf rotation = fixedAxisRotScratch.setFromUnnormalized(result.transform()).normalize();
        Vector3f vector = fixedAxisVecScratch.set(rotation.x, rotation.y, rotation.z);
        float projection = vector.dot(axis);
        Quaternionf twist = fixedAxisTwistScratch.set(axis.x * projection, axis.y * projection,
                axis.z * projection, rotation.w).normalize();
        Matrix4f matrix = fixedAxisMatrixScratch.translationRotateScale(
                result.transform().getTranslation(fixedAxisPosScratch), twist,
                result.transform().getScale(fixedAxisScaleScratch));
        result.set(matrix);
    }

    private void evaluateHierarchyFrom(Bone root, Bone requiredDescendant) {
        if (!isAncestorOf(root, requiredDescendant)) {
            evaluateHierarchy();
            return;
        }
        Bone parent = root.getParent();
        if (parent == null) {
            evaluateHierarchy();
            return;
        }
        Transform parentAbsolute = absoluteTransforms.get(parent);
        if (parentAbsolute == null) {
            evaluateHierarchy();
            return;
        }

        updateQueue.clear();
        Transform rootAbsolute = reusableAbsolute(root);
        composeScratch.set(parentAbsolute);
        rootAbsolute.set(root.getTransform());
        getMorphTransform(root, rootAbsolute);
        composeScratch.mul(rootAbsolute);
        rootAbsolute.set(composeScratch).mul(evaluatedLocalTransform(root));
        updateQueue.add(root);
        recursiveUpdate();
    }

    private static boolean isAncestorOf(Bone ancestor, Bone bone) {
        for (Bone current = bone; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private Bone pmxBone(int pmxIndex) {
        return pmxIndex >= 0 && pmxIndex < pmxBones.length ? pmxBones[pmxIndex] : null;
    }

    private record IkTarget(Vector3f position, float weight) {
        private IkTarget {
            position = new Vector3f(position);
        }
    }

    private void markDirty(Bone bone) {
        dirtyBones.add(bone);
        shouldUpdate = true;
        mutationEpoch++;
    }

    private void requestFullUpdate() {
        shouldUpdate = true;
        fullUpdateRequested = true;
        dirtyBones.clear();
        mutationEpoch++;
    }

    public boolean isMorphUpdated() {
        return !modelInstance.getMorph().getLastUpdatedBones().isEmpty();
    }

    private Set<Bone> collectUpdatedBones() {
        BitSet lastUpdated = modelInstance.getMorph().getLastUpdatedBones();
        Set<Bone> newlyUpdatedBones = new HashSet<>();
        for (int i = lastUpdated.nextSetBit(0); i >= 0; i = lastUpdated.nextSetBit(i + 1)) {
            newlyUpdatedBones.add(skeleton.getBones()[i]);
        }
        lastUpdated.clear();
        if (!newlyUpdatedBones.isEmpty()) {
            for (Bone bone : newlyUpdatedBones) {
                if (!getSkeleton().getBoneMap().containsValue(bone)) continue;
                dirtyBones.add(bone);
            }
            newlyUpdatedBones.clear();
        }
        if (fullUpdateRequested || dirtyBones.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Bone> updatedBones = new HashSet<>();
        for (Bone bone : dirtyBones) {
            collectSubtree(bone, updatedBones);
        }
        return Collections.unmodifiableSet(updatedBones);
    }

    private void collectSubtree(Bone bone, Set<Bone> result) {
        if (bone == null || !result.add(bone) || bone.getChildren() == null) {
            return;
        }
        for (Bone child : bone.getChildren()) {
            collectSubtree(child, result);
        }
    }

    public void collectBoneContexts(List<BoneContext> contexts, Vertex vertex) {
        contexts.clear();
        for (Pair<Bone, Float> weight : vertex.getBinding().getWeights()) {
            Bone bone = weight.getFirst();
            float w = weight.getSecond();
            Transform transform = transforms.getOrDefault(bone, new Transform());
            Transform absTransform = absoluteTransforms.get(bone);
            Pair<Transform, Transform> pair = skeleton.getBoneTransforms().get(bone);
            contexts.add(new BoneContext<>(bone, w, bone.getBoneData(), transform,
                    pair.getFirst(), absTransform, pair.getSecond()));
        }
    }

    /** Compatibility overload for bridge callers. */
    public HashMap<Vertex, Vertex> getVertexTransforms(Model model, Bridge<?> bridge) {
        return getVertexTransforms(model, (ModelGeometryAdapter) bridge);
    }

    public HashMap<Vertex, Vertex> getVertexTransforms(Model model, ModelGeometryAdapter adapter) {
        HashMap<Vertex, Vertex> vertexTransforms = new HashMap<>();
        List<BoneContext> contexts = new ArrayList<>();
        for (Vertex vertex : model.getVertices()) {
            BoneBindingFunc func = adapter.getBoneBindingFunc(model, this, vertex);
            if (func == null) continue;
            collectBoneContexts(contexts, vertex);
            Vertex result = func.apply(vertex, contexts);
            vertexTransforms.put(vertex, result);
        }
        return vertexTransforms;
    }
}
