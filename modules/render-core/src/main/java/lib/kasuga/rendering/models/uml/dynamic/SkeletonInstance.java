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
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.IKLimitation;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.ParentBoneInherit;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxIKBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxIKChain;
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
    private final HashMap<Bone, IkTarget> ikTargets;
    private final HashMap<Bone, IkTarget> frameIkTargets;
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
    private final Vector3f ikTargetPositionScratch = new Vector3f();
    private final Vector3f ikLinkPositionScratch = new Vector3f();
    private final Vector3f ikEffectorPositionScratch = new Vector3f();
    private final Vector3f ikEffectorDirectionScratch = new Vector3f();
    private final Vector3f ikTargetDirectionScratch = new Vector3f();
    private final Vector3f ikAxisScratch = new Vector3f();
    private final Quaternionf ikWorldDeltaScratch = new Quaternionf();
    private final Quaternionf ikWorldRotationScratch = new Quaternionf();
    private final Quaternionf ikLocalDeltaScratch = new Quaternionf();
    private final Vector3f ikEulerScratch = new Vector3f();
    private final Vector3f ikDirectionScratch = new Vector3f();
    private final Matrix4f ikMatrixScratch = new Matrix4f();

    /**
     * bone → IK controllers whose chain includes this bone. MMD semantics:
     * while such a controller's IK is enabled, the link bones are driven by the
     * IK solver alone — authored pose rotations (e.g. VMD thigh keyframes) are
     * ignored, otherwise a motion that animates both the IK target and the thigh
     * directly would double-drive the leg. Built lazily from PMX IK data.
     */
    private Map<Bone, List<Bone>> ikLinksByBone;
    private boolean ikLinksBuilt;

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
        if (solveIk) solvePmxIk();
        lastFullUpdate = fullUpdateRequested || updatedBones.isEmpty();
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
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null || !(bone.getBoneData() instanceof PmxBone pmx) || pmx.ik == null) return false;
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

    /** Sets a persistent world-space target for a PMX IK controller. */
    public boolean setIkTarget(String controllerBone, Vector3f worldTarget, float weight) {
        Bone bone = ikController(controllerBone);
        if (bone == null) return false;
        ikTargets.put(bone, ikTarget(worldTarget, weight));
        requestFullUpdate();
        return true;
    }

    public boolean clearIkTarget(String controllerBone) {
        Bone bone = skeleton.getBoneMap().get(controllerBone);
        if (bone == null || ikTargets.remove(bone) == null) return false;
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
        Bone bone = ikController(controllerBone);
        if (bone == null) return false;
        frameIkTargets.put(bone, ikTarget(worldTarget, weight));
        return true;
    }

    /** Called by the model pose pipeline before its BEFORE_IK effectors. */
    public void clearFrameIkTargets() {
        frameIkTargets.clear();
    }

    private Bone ikController(String name) {
        Bone bone = skeleton.getBoneMap().get(name);
        return bone != null && bone.getBoneData() instanceof PmxBone pmx && pmx.ik != null
                ? bone : null;
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
     * currently enabled. Chain membership is derived once from PMX data; the
     * enable check is a per-frame map lookup (IK enable defaults to true, per
     * MMD). Non-PMX skeletons and non-link bones always return false.
     */
    private boolean isIkDriven(Bone bone) {
        if (!ikLinksBuilt) buildIkLinks();
        List<Bone> controllers = ikLinksByBone.get(bone);
        if (controllers == null || controllers.isEmpty()) return false;
        for (Bone controller : controllers) {
            if (isIkEnabled(controller.getName())) return true;
        }
        return false;
    }

    private void buildIkLinks() {
        ikLinksByBone = new HashMap<>();
        for (Bone controller : pmxBones) {
            if (!(controller.getBoneData() instanceof PmxBone pmx) || pmx.ik == null) continue;
            if (pmx.ik.chains == null) continue;
            for (PmxIKChain chain : pmx.ik.chains) {
                Bone link = pmxBone(chain.boneIndex.intValue());
                if (link == null) continue;
                ikLinksByBone.computeIfAbsent(link, ignored -> new ArrayList<>()).add(controller);
            }
        }
        ikLinksBuilt = true;
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

    private void solvePmxIk() {
        for (Bone controller : skeleton.getBones()) {
            if (!(controller.getBoneData() instanceof PmxBone pmx) || pmx.ik == null
                    || !isIkEnabled(controller.getName())) continue;
            solveIk(controller, pmx.ik);
        }
    }

    /**
     * MMD 方向型 IK 判别：控制器骨与 effector 骨在 bind 姿态下重合（{@code つま先ＩＫ} 挂在
     * {@code 足ＩＫ} 下而 つま先 挂在 足首 下，bind 时都在脚趾）且 IK 链只有一根 link。
     * 普通位置型 IK（{@code 足ＩＫ}）链有两根（ひざ, 足），不会被误判。
     */
    private boolean isDirectionIk(Bone controller, PmxIKBone ik, Bone effector) {
        if (ik.chains == null || ik.chains.length != 1) return false;
        if (controller.getBoneData() instanceof PmxBone pmx
                && pmx.tailObject instanceof Vector3f) {
            Transform controllerBind = controller.getTransform();
            Transform effectorBind = effector.getTransform();
            return controllerBind.getPosition().distanceSquared(effectorBind.getPosition()) < 1e-4f;
        }
        return false;
    }

    private void solveIk(Bone controller, PmxIKBone ik) {
        Bone effector = pmxBone(ik.boneIndex.intValue());
        if (effector == null || absoluteTransforms.get(controller) == null) return;
        Matrix4f controllerAbsolute = absoluteTransforms.get(controller).transform();
        IkTarget override = frameIkTargets.getOrDefault(controller, ikTargets.get(controller));
        // Lives for the whole controller solve; never aliased by inner-loop scratches.
        Vector3f targetPosition = ikTargetPositionScratch.set(controllerAbsolute.m30(),
                controllerAbsolute.m31(), controllerAbsolute.m32());
        if (override != null) targetPosition.lerp(override.position, override.weight);
        // MMD 方向型 IK（つま先ＩＫ 等）：控制器骨与 effector 骨在 bind 姿态下重合、且链只有一根
        // （足首）—— 目标不是"把 effector 拉向控制器位置"，而是"让 effector 骨的方向对齐控制器
        // 的方向"。目标 = effector 的 bind 脚趾方向 × 控制器动画旋转偏移（VMD 只写 つま先ＩＫ 的
        // 旋转来控制脚尖）：bind 时零修正，脚趾方向只跟随 VMD。用位置语义时，控制器挂 IK 骨下、
        // effector 挂腿链下，父链移动差异会把目标甩离脚趾 → 踝关节猛转、脚底板翻起。
        if (isDirectionIk(controller, ik, effector)) {
            Transform effectorAbsolute = absoluteTransforms.get(effector);
            if (effectorAbsolute != null) {
                Vector3f dir = ikDirectionScratch.set(effector.getTransform().getPosition()).normalize();
                Transform animRotation = transforms.get(controller);
                if (animRotation != null) animRotation.getRotation().transform(dir);
                targetPosition.set(effectorAbsolute.getPosition()).add(dir);
            }
        }
        int iterations = Math.min(Math.max(ik.CCD_Count, 0), 256);
        boolean corrected = false;
                for (int iteration = 0; iteration < iterations; iteration++) {
            boolean converged = false;
            for (PmxIKChain chain : ik.chains) {
                Bone link = pmxBone(chain.boneIndex.intValue());
                Transform linkState = link == null ? null : absoluteTransforms.get(link);
                if (linkState == null) continue;
                Matrix4f linkAbsolute = linkState.transform();
                Vector3f linkPosition = ikLinkPositionScratch.set(linkAbsolute.m30(),
                        linkAbsolute.m31(), linkAbsolute.m32());
                Matrix4f effectorAbsolute = absoluteTransforms.get(effector).transform();
                Vector3f effectorDirection = ikEffectorDirectionScratch.set(effectorAbsolute.m30(),
                        effectorAbsolute.m31(), effectorAbsolute.m32()).sub(linkPosition);
                Vector3f targetDirection = ikTargetDirectionScratch.set(targetPosition).sub(linkPosition);
                if (effectorDirection.lengthSquared() < 1e-10f || targetDirection.lengthSquared() < 1e-10f) continue;
                effectorDirection.normalize();
                targetDirection.normalize();
                float angle = (float) Math.acos(Math.clamp(effectorDirection.dot(targetDirection), -1f, 1f));
                angle = Math.min(angle, Math.abs(ik.boneRotationLimit));
                if (angle < 1e-6f) continue;
                Vector3f axis = effectorDirection.cross(targetDirection, ikAxisScratch);
                if (axis.lengthSquared() < 1e-10f) {
                    if (effectorDirection.dot(targetDirection) > 0f) continue;
                    if (Math.abs(effectorDirection.x) < 0.9f) {
                        axis.set(effectorDirection).cross(1f, 0f, 0f);
                    } else {
                        axis.set(effectorDirection).cross(0f, 1f, 0f);
                    }
                }
                Vector3f normalizedAxis = axis.normalize();
                Quaternionf worldDelta = ikWorldDeltaScratch.rotationAxis(angle,
                        normalizedAxis.x, normalizedAxis.y, normalizedAxis.z);
                Quaternionf worldRotation = ikWorldRotationScratch.setFromUnnormalized(linkAbsolute).normalize();
                Quaternionf delta = ikLocalDeltaScratch.set(worldRotation).invert()
                        .mul(worldDelta)
                        .mul(worldRotation)
                        .normalize();
                Transform correction = ikTransforms.computeIfAbsent(link, ignored -> new Transform());
                correction.mul(delta);
                if (chain.useRotationLimit && chain.limit != null) clampIk(correction, chain.limit);
                corrected = true;
                evaluateHierarchyFrom(link, effector);
                Matrix4f refreshedEffector = absoluteTransforms.get(effector).transform();
                if (ikLinkPositionScratch.set(refreshedEffector.m30(), refreshedEffector.m31(), refreshedEffector.m32())
                        .distanceSquared(targetPosition) < 1e-8f) {
                    converged = true;
                    break;
                }
            }
            if (converged) break;
        }
        // Refresh grant dependencies and other branches once per controller.
        // Previously this full traversal happened after every chain correction.
        if (corrected) evaluateHierarchy();
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

    private void clampIk(Transform correction, IKLimitation limit) {
        Vector3f euler = correction.getRotation().getEulerAnglesXYZ(ikEulerScratch);
        euler.set(
                Math.clamp(euler.x, limit.min().x, limit.max().x),
                Math.clamp(euler.y, limit.min().y, limit.max().y),
                Math.clamp(euler.z, limit.min().z, limit.max().z));
        correction.set(ikMatrixScratch.rotationXYZ(euler.x, euler.y, euler.z));
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
