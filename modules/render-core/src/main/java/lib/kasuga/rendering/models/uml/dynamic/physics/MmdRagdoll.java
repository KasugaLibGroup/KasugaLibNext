package lib.kasuga.rendering.models.uml.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.BallJoint;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.DragSettings;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.PlaneCollider;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RayHit;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.StaticBoxCollider;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.StaticEnvironmentMesh;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.PmxJoint;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.PmxRigidBody;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * PMX/PMD and explicitly-profiled glTF adapter over the native Box3D-backed
 * {@link RigidBodyWorld}.
 *
 * <p>All integration, collision, joints, sleeping and dragging constraints
 * are solved by Box3D; this class only maps authored/generated
 * rigid bodies and joints onto it, drives kinematic bodies from animated
 * bones, and writes physical poses back. PMX mode 0 bodies follow their
 * bone, while mode 1 and mode 2 bodies are fully dynamic; mode 2 preserves
 * the animated bone translation when its physical rotation is written back.
 * authored limits onto Box3D's spherical-joint cone/twist model.</p>
 */
public final class MmdRagdoll implements AutoCloseable {
    private static final int PROFILE_SECONDARY_SUBSTEP_COUNT = 8;
    private final ModelInstance instance;
    private final SkeletonInstance skeleton;
    private final Vector3f modelScale;
    private final int pmxBoneOffset;
    private final Map<Integer, Bone> indexedBones;
    private final Set<Bone> gltfSkinBones;
    private final Map<Bone, Transform> gltfBindWorlds;
    private final List<Body> bodies;
    private final List<Body> exposedBodies;
    private final List<Joint> joints;
    private final Map<Bone, Body> bodyByBone = new IdentityHashMap<>();
    private final Map<Integer, Body> bodyByRigidBodyIndex = new HashMap<>();
    private final Bone profileMotionRoot;
    private final Body profileRootBody;
    private RigidBodyWorld world;
    private MmdPhysicsScene physicsScene;
    private int sharedSelfCollisionGroup;
    private boolean sharedSelfCollisionsEnabled;
    private final Profile profile;
    private boolean enabled = true;
    /** Mutation epoch of the skeleton at the last kinematic evaluation. */
    private long kinematicEvaluationEpoch = -1;
    /** False once physics writeback (or a step boundary) invalidated the evaluation. */
    private boolean kinematicEvaluationFresh;
    private final Set<Body> bodySet = Collections.newSetFromMap(new IdentityHashMap<>());

    // The animation/IK pose is evaluated once per step() and cached per body;
    // nothing mutates skeleton inputs during world.step, so re-evaluating per
    // fixed step (the previous behavior) recomputed identical hierarchies.
    private final RigidBodyWorld.KinematicDriver driver = new RigidBodyWorld.KinematicDriver() {
        @Override public void beginStep(RigidBodyWorld simulatedWorld) {}

        @Override public Frames.Pose kinematicTarget(SimBody body) {
            return ((Body) body).animationTargetCache;
        }
    };

    public MmdRagdoll(ModelInstance instance) {
        this(instance, null, null);
    }

    /**
     * Creates a ragdoll from an explicit primary-body registration. A null
     * profile preserves the original PMX behavior and simulates every authored
     * rigid body, which is useful for small assets and compatibility tests.
     */
    public MmdRagdoll(ModelInstance instance, Profile profile) {
        this(instance, profile, null);
    }

    /** Creates a model participant owned and stepped by a shared physics scene. */
    public MmdRagdoll(ModelInstance instance, Profile profile, MmdPhysicsScene physicsScene) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.skeleton = instance.getSkeletonInstance();
        this.physicsScene = physicsScene;
        // Bone evaluation and Box3D share one origin-relative coordinate
        // system. The high-precision world anchor remains on the skeleton and
        // never enters float matrices or the native solver.
        if (physicsScene == null) this.skeleton.enableFloatingOrigin();
        else this.skeleton.rebaseFloatingOrigin(physicsScene.worldOrigin());
        this.skeleton.updateTransform();
        this.profile = profile;
        List<PmxRigidBody> bodyDefinitions;
        List<PmxJoint> jointDefinitions;
        if (instance.getModel().getModelData() instanceof MmdModelData data) {
            this.modelScale = data.modelScale();
            this.pmxBoneOffset = data.pmxBoneCount() >= 0
                    && skeleton.getSkeleton().getBones().length == data.pmxBoneCount() + 1 ? 1 : 0;
            this.indexedBones = null;
            this.gltfSkinBones = Set.of();
            this.gltfBindWorlds = Map.of();
            bodyDefinitions = data.tail().rigidBodies();
            jointDefinitions = data.tail().joints();
        } else if (instance.getModel().getModelData() instanceof GltfModelData data && profile != null) {
            this.modelScale = data.modelScale();
            this.pmxBoneOffset = 0;
            this.indexedBones = data.boneByNode();
            Set<Bone> skinBones = new HashSet<>();
            data.asset().skins().forEach(skin -> {
                for (int node : skin.jointNodeIndices()) {
                    Bone bone = data.boneByNode().get(node);
                    if (bone != null) skinBones.add(bone);
                }
            });
            this.gltfSkinBones = Set.copyOf(skinBones);
            Map<Bone, Transform> bindWorlds = new IdentityHashMap<>();
            data.boneByNode().values().forEach(bone -> {
                Transform bind = skeleton.getAbsoluteTransforms().get(bone);
                if (bind != null) bindWorlds.put(bone, bind.copy());
            });
            this.gltfBindWorlds = Collections.unmodifiableMap(bindWorlds);
            bodyDefinitions = gltfBodyDefinitions(data);
            jointDefinitions = List.of();
        } else {
            throw new IllegalArgumentException(profile == null
                    ? "Model does not contain authored PMX/PMD physics metadata; glTF requires an explicit profile"
                    : "Model format does not expose a skeleton usable by the ragdoll adapter");
        }
        if (profile == null) {
            this.bodies = buildBodies(bodyDefinitions);
            this.joints = buildJoints(jointDefinitions);
            this.profileMotionRoot = null;
            this.profileRootBody = null;
        } else {
            RegisteredPhysics registered = buildRegisteredPhysics(bodyDefinitions, jointDefinitions, profile);
            this.bodies = registered.bodies();
            this.joints = registered.joints();
            this.profileMotionRoot = registered.motionRoot();
            this.profileRootBody = registered.rootBody();
            this.bodyByRigidBodyIndex.putAll(registered.bodyByDefinition());
        }
        this.exposedBodies = bodies.stream().filter(body -> !body.secondaryAnchorBody).toList();
        // Positions and collider dimensions are converted from PMX units into
        // world units by modelScale. Gravity stays in world units per second
        // squared so a 1/12-scale Minecraft model does not fall in slow motion.
        if (physicsScene == null) {
            this.world = new RigidBodyWorld(bodies, joints,
                    profile == null ? RigidBodyWorld.DEFAULT_SUBSTEP_COUNT
                            : profile.includeSecondaryBodies ? PROFILE_SECONDARY_SUBSTEP_COUNT
                            : RigidBodyWorld.PROFILE_SUBSTEP_COUNT);
            this.world.setWorldOrigin(skeleton.getWorldOrigin());
        } else {
            this.world = physicsScene.world();
            this.sharedSelfCollisionGroup = physicsScene.allocateSelfCollisionGroup();
            this.sharedSelfCollisionsEnabled = profile == null;
            for (Body body : bodies) {
                body.selfCollisionGroup = sharedSelfCollisionsEnabled ? 0 : sharedSelfCollisionGroup;
            }
        }
        // Box3D assigns every shape in one ragdoll a negative group index so
        // the character does not collide with itself. Authored PMX secondary
        // physics keeps its original per-body masks instead.
        if (profile != null && physicsScene == null) world.setSelfCollisionsEnabled(false);
        for (Body body : bodies) {
            body.wireWake(world::wake);
            if (body.bone != null && body.source.mode() != 0) bodyByBone.putIfAbsent(body.bone, body);
        }
        if (profile == null) {
            for (int index = 0; index < bodies.size(); index++) {
                bodyByRigidBodyIndex.put(index, bodies.get(index));
            }
        }
        bodySet.addAll(bodies);
        if (physicsScene != null) physicsScene.register(this);
    }

    public Profile profile() {
        return profile;
    }

    public MmdPhysicsScene physicsScene() { return physicsScene; }

    List<Body> allBodies() { return bodies; }
    List<Joint> allJoints() { return joints; }
    ModelInstance modelInstance() { return instance; }

    public List<Body> bodies() {
        return exposedBodies;
    }

    public List<Joint> joints() {
        return Collections.unmodifiableList(joints);
    }

    /** High-precision world anchor for this origin-relative simulation. */
    public Vector3d worldOrigin() {
        return world.worldOrigin();
    }

    public Vector3f worldToSimulation(double x, double y, double z) {
        return world.worldToLocal(x, y, z);
    }

    public Vector3d simulationToWorld(Vector3f local) {
        return world.localToWorld(local);
    }

    public Vector3d worldPosition(Body body) {
        if (!bodySet.contains(Objects.requireNonNull(body, "body"))) {
            throw new IllegalArgumentException("body does not belong to this ragdoll");
        }
        return world.localToWorld(body.pose.position);
    }

    /** Finds a body by its PMX rigid-body index or profiled glTF node index. */
    public Optional<Body> body(int rigidBodyIndex) {
        return Optional.ofNullable(bodyByRigidBodyIndex.get(rigidBodyIndex));
    }

    /** Finds the dynamic body directly associated with a skeleton bone. */
    public Optional<Body> body(Bone bone) {
        return Optional.ofNullable(bodyByBone.get(Objects.requireNonNull(bone, "bone")));
    }

    /** Finds a body by bone name without exposing skeleton traversal to API callers. */
    public Optional<Body> body(String boneName) {
        Objects.requireNonNull(boneName, "boneName");
        return bodies.stream().filter(body -> body.bone != null && boneName.equals(body.bone.getName()))
                .findFirst();
    }

    /** Same-frame animated/IK target of a body before physical writeback. */
    public Frames.Pose animationTarget(Body body) {
        if (!bodySet.contains(body)) throw new IllegalArgumentException("body does not belong to this ragdoll");
        return new Frames.Pose(body.animationTargetCache.position, body.animationTargetCache.rotation);
    }

    /** Applies a world-space impulse at the body's center of mass. */
    public boolean applyImpulse(Body body, Vector3f impulse) {
        return world.applyImpulse(body, impulse);
    }

    /** Applies an impulse at an origin-local simulation point, including torque. */
    public boolean applyImpulse(Body body, Vector3f impulse, Vector3f simulationPoint) {
        return world.applyImpulse(body, impulse, simulationPoint);
    }

    public boolean applyImpulseWorld(Body body, Vector3f impulse,
                                     double pointX, double pointY, double pointZ) {
        return world.applyImpulse(body, impulse, world.worldToLocal(pointX, pointY, pointZ));
    }

    /** Applies a world-space angular impulse through the body's inertia tensor. */
    public boolean applyAngularImpulse(Body body, Vector3f impulse) {
        return world.applyAngularImpulse(body, impulse);
    }

    public boolean applyForce(Body body, Vector3f force) {
        return world.applyForce(body, force);
    }

    public boolean applyForce(Body body, Vector3f force, Vector3f simulationPoint) {
        return world.applyForce(body, force, simulationPoint);
    }

    public boolean applyForceWorld(Body body, Vector3f force,
                                   double pointX, double pointY, double pointZ) {
        return world.applyForce(body, force, world.worldToLocal(pointX, pointY, pointZ));
    }

    public boolean applyTorque(Body body, Vector3f torque) {
        return world.applyTorque(body, torque);
    }

    public boolean setGravityScale(Body body, float scale) {
        return world.setGravityScale(body, scale);
    }

    public float gravityScale(Body body) {
        return world.gravityScale(body);
    }

    /** Finds the closest shape intersected by an origin-local simulation ray. */
    public Optional<RayHit> raycast(Vector3f origin, Vector3f direction, float maximumDistance) {
        return world.raycast(origin, direction, maximumDistance);
    }

    /** Raycasts from a double-precision world position without a large float cast. */
    public Optional<RayHit> raycastWorld(double originX, double originY, double originZ,
                                         Vector3f direction, float maximumDistance) {
        return world.raycast(world.worldToLocal(originX, originY, originZ), direction, maximumDistance);
    }

    /** Starts a soft constraint at an origin-local point on a dynamic body. */
    public boolean beginDrag(Body body, Vector3f simulationPoint) {
        return world.beginDrag(body, simulationPoint);
    }

    public boolean beginDragWorld(Body body, double x, double y, double z) {
        return world.beginDrag(body, world.worldToLocal(x, y, z));
    }

    public boolean beginDrag(RayHit hit) {
        return world.beginDrag(hit);
    }

    /** Updates the world-space mouse target and derives a bounded target velocity. */
    public void updateDragTarget(Vector3f worldTarget, float frameSeconds) {
        world.updateDragTarget(worldTarget, frameSeconds);
    }

    public void updateDragTargetWorld(double x, double y, double z, float frameSeconds) {
        world.updateDragTarget(world.worldToLocal(x, y, z), frameSeconds);
    }

    public void endDrag() {
        world.endDrag();
    }

    public boolean dragging() {
        return world.dragging();
    }

    public Body draggedBody() {
        SimBody dragged = world.draggedBody();
        try {
            return (Body) dragged;
        } catch (ClassCastException exception) {
            return null;
        }
    }

    public DragSettings dragSettings() {
        return world.dragSettings();
    }

    public void setDragSettings(DragSettings dragSettings) {
        world.setDragSettings(dragSettings);
    }

    public Vector3f gravity() {
        return world.gravity();
    }

    public void setGravity(Vector3f gravity) {
        world.setGravity(gravity);
    }

    public int solverIterations() {
        return world.solverIterations();
    }

    public void setSolverIterations(int solverIterations) {
        world.setSolverIterations(solverIterations);
    }

    public boolean collisionsEnabled() {
        return world.collisionsEnabled();
    }

    public void setCollisionsEnabled(boolean enabled) {
        world.setCollisionsEnabled(enabled);
    }

    public boolean continuousCollisionEnabled() {
        return world.continuousCollisionEnabled();
    }

    /** Enables swept collision detection for bodies that cross a collider within one substep. */
    public void setContinuousCollisionEnabled(boolean enabled) {
        world.setContinuousCollisionEnabled(enabled);
    }

    public boolean selfCollisionsEnabled() {
        return physicsScene == null ? world.selfCollisionsEnabled() : sharedSelfCollisionsEnabled;
    }

    /** Enables contacts between bodies belonging to this same ragdoll. */
    public void setSelfCollisionsEnabled(boolean enabled) {
        if (physicsScene == null) {
            world.setSelfCollisionsEnabled(enabled);
            return;
        }
        sharedSelfCollisionsEnabled = enabled;
        for (Body body : bodies) body.selfCollisionGroup = enabled ? 0 : sharedSelfCollisionGroup;
        // The native filters cache group indices; reapply the scene policy.
        world.setSelfCollisionsEnabled(true);
    }

    /** Unique body-to-body contacts from the latest Box3D step. */
    public int selfContactCount() {
        return world.selfContactCount();
    }

    /** Number of simulated bodies touching static geometry after the latest Box3D step. */
    public int staticContactBodyCount() {
        return world.staticContactBodyCount();
    }

    /** Impact speed below which restitution is suppressed (Box3D default: 1 m/s). */
    public float restitutionThreshold() {
        return world.restitutionThreshold();
    }

    public void setRestitutionThreshold(float threshold) {
        world.setRestitutionThreshold(threshold);
    }

    public float simulationHertz() {
        return world.simulationHertz();
    }

    /** Sets the fixed world-step frequency. Frame delta is accumulated, never used as a variable solver step. */
    public void setSimulationHertz(float simulationHertz) {
        world.setSimulationHertz(simulationHertz);
    }

    public int substepCount() {
        return world.substepCount();
    }

    public void setSubstepCount(int substepCount) {
        world.setSubstepCount(substepCount);
    }

    public float constraintHertz() {
        return world.constraintHertz();
    }

    public float constraintDampingRatio() {
        return world.constraintDampingRatio();
    }

    /** Box3D-style joint softness expressed as frequency and damping ratio. */
    public void setConstraintTuning(float hertz, float dampingRatio) {
        world.setConstraintTuning(hertz, dampingRatio);
    }

    /** Numerical safety limits; defaults are high enough not to cap ordinary falling motion. */
    public void setSpeedLimits(float maxLinearSpeed, float maxAngularSpeed) {
        world.setSpeedLimits(maxLinearSpeed, maxAngularSpeed);
    }

    public int maxFixedStepsPerUpdate() {
        return world.maxFixedStepsPerUpdate();
    }

    /**
     * Bounds catch-up work performed by one {@link #step(float)} call. Excess
     * accumulated time is discarded to prevent a persistent catch-up spiral.
     */
    public void setMaxFixedStepsPerUpdate(int maximum) {
        world.setMaxFixedStepsPerUpdate(maximum);
    }

    /** Number of fixed world steps completed by the latest update. */
    public int lastFixedStepCount() {
        return world.lastFixedStepCount();
    }

    /** Total wall-clock seconds discarded by the configured catch-up budget. */
    public float droppedSimulationTime() {
        return world.droppedSimulationTime();
    }

    /**
     * Adds an origin-local infinite static plane. The allowed half-space satisfies
     * {@code dot(point, normal) >= offset}.
     */
    public PlaneCollider addPlaneCollider(Vector3f normal, float offset,
                                          float friction, float restitution) {
        return world.addPlaneCollider(normal, offset, friction, restitution);
    }

    /** Convenience for an upward-facing plane at the supplied Y coordinate. */
    public PlaneCollider addGroundPlane(float y, float friction, float restitution) {
        return world.addGroundPlane(y, friction, restitution);
    }

    public PlaneCollider addGroundPlaneWorld(double y, float friction, float restitution) {
        return world.addGroundPlane((float) (y - world.worldOrigin().y), friction, restitution);
    }

    public void removePlaneCollider(PlaneCollider plane) {
        world.removePlaneCollider(plane);
    }

    public void clearPlaneColliders() {
        world.clearPlaneColliders();
    }

    /** Adds a static axis-aligned collision box in origin-local coordinates. */
    public StaticBoxCollider addStaticBoxCollider(Vector3f minimum, Vector3f maximum,
                                                  float friction, float restitution) {
        return world.addStaticBoxCollider(minimum, maximum, friction, restitution);
    }

    public StaticBoxCollider addStaticBoxColliderWorld(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float friction, float restitution) {
        return world.addStaticBoxCollider(world.worldToLocal(minX, minY, minZ),
                world.worldToLocal(maxX, maxY, maxZ), friction, restitution);
    }

    public void removeStaticBoxCollider(StaticBoxCollider box) {
        world.removeStaticBoxCollider(box);
    }

    public void clearStaticBoxColliders() {
        world.clearStaticBoxColliders();
    }

    /**
     * Creates a stable, incrementally editable environment mesh. Callers may
     * replace individual cells without unregistering unchanged terrain.
     */
    public StaticEnvironmentMesh addEnvironmentMesh(float friction, float restitution) {
        return world.addEnvironmentMesh(friction, restitution);
    }

    public void removeEnvironmentMesh(StaticEnvironmentMesh mesh) {
        world.removeEnvironmentMesh(mesh);
    }

    public lib.kasuga.rendering.models.uml.dynamic.physics.core.CollisionEnvironment collisionEnvironment() {
        return world.collisionEnvironment();
    }

    /** Installs an optional environment that refreshes static colliders once per physics frame. */
    public void setCollisionEnvironment(
            lib.kasuga.rendering.models.uml.dynamic.physics.core.CollisionEnvironment environment) {
        world.setCollisionEnvironment(environment);
    }

    /** Migrates this ragdoll and its bodies to another shared physics scene (or standalone if null). */
    public void moveTo(MmdPhysicsScene newScene) {
        if (this.physicsScene == newScene) return;
        Vector3d oldOrigin = world.worldOrigin();
        if (this.physicsScene != null) {
            this.physicsScene.detach(this);
        } else if (this.world != null) {
            this.world.close();
        }

        this.physicsScene = newScene;
        if (newScene == null) {
            this.skeleton.enableFloatingOrigin();
            rebaseBodyPoses(oldOrigin, skeleton.getWorldOrigin());
            this.world = new RigidBodyWorld(bodies, joints,
                    profile == null ? RigidBodyWorld.DEFAULT_SUBSTEP_COUNT
                            : profile.includeSecondaryBodies ? PROFILE_SECONDARY_SUBSTEP_COUNT
                            : RigidBodyWorld.PROFILE_SUBSTEP_COUNT);
            this.world.setWorldOrigin(skeleton.getWorldOrigin());
            if (profile != null) this.world.setSelfCollisionsEnabled(false);
            for (Body body : bodies) {
                body.wireWake(world::wake);
                body.selfCollisionGroup = 0;
            }
        } else {
            this.skeleton.rebaseFloatingOrigin(newScene.worldOrigin());
            rebaseBodyPoses(oldOrigin, newScene.worldOrigin());
            this.world = newScene.world();
            this.sharedSelfCollisionGroup = newScene.allocateSelfCollisionGroup();
            this.sharedSelfCollisionsEnabled = profile == null;
            for (Body body : bodies) {
                body.wireWake(world::wake);
                body.selfCollisionGroup = sharedSelfCollisionsEnabled ? 0 : sharedSelfCollisionGroup;
            }
            newScene.register(this);
            if (!enabled) {
                for (Body body : bodies) world.setBodyEnabled(body, false);
            }
        }
        evaluateAnimationTarget();
        this.world.wake();
    }

    private void rebaseBodyPoses(Vector3d oldOrigin, Vector3d newOrigin) {
        Vector3f offset = new Vector3f(
                (float) (oldOrigin.x - newOrigin.x),
                (float) (oldOrigin.y - newOrigin.y),
                (float) (oldOrigin.z - newOrigin.z));
        if (offset.lengthSquared() == 0f) return;
        for (Body body : bodies) {
            body.pose.position.add(offset);
            body.previousPose.position.add(offset);
            body.interpolationPose.position.add(offset);
            body.animationTargetCache.position.add(offset);
        }
    }

    /** Releases dragging and environment resources and restores the animated pose. */
    @Override
    public void close() {
        if (physicsScene == null) world.close();
        else physicsScene.detach(this);
        setEnabled(false);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean sleeping() {
        return world.sleeping();
    }

    /** Seconds accumulated toward automatic sleeping, for diagnostics. */
    public float sleepTime() {
        return world.sleepTime();
    }

    public boolean sleepingEnabled() {
        return world.sleepingEnabled();
    }

    /** Enables or disables automatic whole-island sleeping. */
    public void setSleepingEnabled(boolean enabled) {
        world.setSleepingEnabled(enabled);
    }

    /** Configures whole-island sleeping using speeds in world units/sec and radians/sec. */
    public void setSleepingThresholds(float linearSpeed, float angularSpeed, float delaySeconds) {
        world.setSleepingThresholds(linearSpeed, angularSpeed, delaySeconds);
    }

    /** Wakes the complete articulated island after an external state change. */
    public void wake() {
        world.wake();
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) {
            if (bodySet.contains(world.draggedBody())) world.endDrag();
            if (physicsScene != null) {
                for (Body body : bodies) world.setBodyEnabled(body, false);
            }
            skeleton.clearPhysicsTransforms();
            skeleton.updateTransform();
        } else {
            reset();
            if (physicsScene != null) {
                for (Body body : bodies) world.setBodyEnabled(body, true);
                world.wake();
            }
        }
    }

    /** Resets every body to the current animated/IK pose and clears velocity. */
    public void reset() {
        if (physicsScene == null) world.resetState();
        else world.wake();
        evaluateAnimationTarget();
        for (Body body : bodies) {
            Frames.Pose target = body.animationTargetCache;
            body.pose.set(target);
            body.previousPose.set(target);
            body.interpolationPose.set(target);
            body.linearVelocityRef().zero();
            body.angularVelocityRef().zero();
        }
        applyToSkeleton(1f);
        kinematicEvaluationFresh = false;
    }

    /** Advances the ragdoll and writes the resulting dynamic body pose to bones. */
    public void step(float deltaSeconds) {
        if (physicsScene != null) {
            throw new IllegalStateException("shared ragdolls must be stepped through MmdPhysicsScene.step");
        }
        if (!enabled || !(deltaSeconds > 0f) || !Float.isFinite(deltaSeconds)) return;
        long profileStart = ModelProfiler.start();
        evaluateAnimationTarget();
        world.step(deltaSeconds, driver);
        applyToSkeleton(world.interpolationAlpha());
        // Physics writeback changed the skeleton state, so the next tick must
        // rebuild the kinematic target even if no other input changed.
        kinematicEvaluationFresh = false;
        if (profileStart != 0L) {
            ModelProfiler.record("physics.mmd.step", profileStart,
                    "bodies=" + bodies.size() + " joints=" + joints.size()
                            + " fixedSteps=" + world.lastFixedStepCount()
                            + " substeps=" + world.lastFixedStepCount() * world.substepCount());
        }
    }

    void prepareSharedStep() {
        evaluateAnimationTarget();
    }

    Frames.Pose sharedKinematicTarget(SimBody body) {
        if (!(body instanceof Body value) || !bodySet.contains(value)) {
            return new Frames.Pose(body.positionRef(), body.rotationRef());
        }
        return value.animationTargetCache;
    }

    void finishSharedStep(float interpolationAlpha) {
        applyToSkeleton(interpolationAlpha);
        kinematicEvaluationFresh = false;
    }

    void onSharedSceneClosed() {
        enabled = false;
        skeleton.clearPhysicsTransforms();
        skeleton.updateTransform();
        kinematicEvaluationFresh = false;
    }

    /**
     * Re-evaluates the kinematic (animation + IK) pose this ragdoll tracks and
     * refreshes every body's cached target. Skipped when no skeleton input has
     * changed since the last evaluation — controllers mounted before the
     * physics stage and {@link #step(float)} then share one hierarchy solve.
     */
    public void evaluateAnimationTarget() {
        long epoch = skeleton.getMutationEpoch();
        if (kinematicEvaluationFresh && epoch == kinematicEvaluationEpoch && !skeleton.isMorphUpdated()) return;
        skeleton.clearPhysicsTransforms();
        skeleton.updateTransform();
        kinematicEvaluationEpoch = skeleton.getMutationEpoch();
        kinematicEvaluationFresh = true;
        for (Body body : bodies) {
            Frames.Pose target = body.kinematicFollowBody == null
                    ? bodyTarget(body)
                    : Frames.compose(body.kinematicFollowBody.pose, body.kinematicFollowOffset);
            body.animationTargetCache.set(target);
        }
    }

    private Frames.Pose bodyTarget(Body body) {
        if (body.bone == null) return body.pose;
        Transform boneTransform = skeleton.getAbsoluteTransforms().get(body.bone);
        return boneTransform == null ? body.pose : Frames.compose(Frames.poseOf(boneTransform), body.boneToBody);
    }

    private Frames.Pose modelPose(Vector3f position, Quaternionf rotation) {
        Transform root = skeleton.getTransform();
        return new Frames.Pose(root.copy().apply(position),
                root.getRotation().mul(rotation, new Quaternionf()).normalize());
    }

    /** Interpolates the completed physics pose for rendering. */
    private void applyToSkeleton(float interpolationAlpha) {
        Map<Bone, Frames.Pose> desired = new IdentityHashMap<>();
        for (Map.Entry<Bone, Body> entry : bodyByBone.entrySet()) {
            Body body = entry.getValue();
            Frames.Pose renderedBody = Frames.interpolate(body.interpolationPose, body.pose, interpolationAlpha);
            Frames.Pose bonePose = Frames.compose(renderedBody, Frames.inverse(body.boneToBody));
            desired.put(entry.getKey(), bonePose);
        }
        if (profileMotionRoot != null && profileRootBody != null) {
            Frames.Pose physicalRoot = desired.get(profileRootBody.bone);
            Transform animatedBody = skeleton.getAbsoluteTransforms().get(profileRootBody.bone);
            Transform animatedMotionRoot = skeleton.getAbsoluteTransforms().get(profileMotionRoot);
            if (physicalRoot != null && animatedBody != null && animatedMotionRoot != null) {
                // Move the skeleton root by the complete physical-root delta,
                // not translation alone. PMX rigs often place a non-physical
                // waist/common parent above both upper- and lower-body branches;
                // without this rotation those branches occupy different frames
                // and vertices weighted across them tear into long triangles.
                Frames.Pose physicalDelta = Frames.compose(physicalRoot,
                        Frames.inverse(Frames.poseOf(animatedBody)));
                desired.put(profileMotionRoot,
                        Frames.compose(physicalDelta, Frames.poseOf(animatedMotionRoot)));
            }
        }
        Map<Bone, Transform> physicsPose = new IdentityHashMap<>();
        if (indexedBones != null) {
            Map<Bone, Transform> affineDesired = new IdentityHashMap<>();
            desired.forEach((bone, pose) -> affineDesired.put(bone,
                    preserveAffine(skeleton.getAbsoluteTransforms().get(bone), pose)));
            followGltfSkinJoints(affineDesired);
            collectAffinePhysicsPose(skeleton.getSkeleton().getRoot(), null, affineDesired, physicsPose);
        } else {
            if (profile != null) followNonPhysicalBones(desired);
            collectRigidPhysicsPose(skeleton.getSkeleton().getRoot(), null, desired, physicsPose);
        }
        skeleton.applyPhysicsTransforms(physicsPose);
        skeleton.updateTransformAfterPhysics();
    }

    /**
     * Every helper bone keeps its
     * animated world-space relation to the nearest physical ancestor. This
     * avoids re-evaluating PMX grant/IK helpers on top of an already physical
     * parent, which otherwise separates the rendered joint from its body
     * anchor and appears as skeletal stretching.
     */
    private void followNonPhysicalBones(Map<Bone, Frames.Pose> desired) {
        for (Bone bone : skeleton.getSkeleton().getBones()) {
            if (desired.containsKey(bone) || bodyByBone.containsKey(bone)) continue;
            Bone physicalAncestor = nearestPhysicalAncestor(bone);
            if (physicalAncestor == null) continue;
            Body ancestorBody = bodyByBone.get(physicalAncestor);
            if (ancestorBody != null && ancestorBody.authoredSecondaryBody) {
                // Terminal/helper bones below a skirt or hair body already
                // inherit that body's rotation through the ordinary skeleton
                // hierarchy. Giving them an independent world-space target
                // would reintroduce the profile-root delta and stretch the
                // final unbodied segment away from its parent.
                continue;
            }
            Frames.Pose physicalPose = desired.get(physicalAncestor);
            Transform animatedAncestor = skeleton.getAbsoluteTransforms().get(physicalAncestor);
            Transform animatedBone = skeleton.getAbsoluteTransforms().get(bone);
            if (physicalPose == null || animatedAncestor == null || animatedBone == null) continue;
            desired.put(bone, Frames.compose(physicalPose,
                    Frames.relative(Frames.poseOf(animatedAncestor), Frames.poseOf(animatedBone))));
        }
    }

    private Bone nearestPhysicalAncestor(Bone bone) {
        for (Bone current = bone.getParent(); current != null; current = current.getParent()) {
            if (bodyByBone.containsKey(current)) return current;
        }
        return null;
    }

    /**
     * Applies the exact glTF skin-joint rule used by the source runtime:
     * {@code currentPhysical * inverse(bindPhysical) * bindJoint}.
     *
     * <p>Using a decomposed translation/rotation relationship here loses the
     * affine part of the bind transform. The error is especially visible on
     * facial, eye and hair joints, and opens seams at elbows and knees once a
     * physical ancestor rotates.</p>
     */
    private void followGltfSkinJoints(Map<Bone, Transform> desired) {
        for (Bone bone : gltfSkinBones) {
            if (desired.containsKey(bone) || bodyByBone.containsKey(bone)) continue;
            Bone physicalAncestor = nearestPhysicalAncestor(bone);
            if (physicalAncestor == null) continue;
            Transform physicalWorld = desired.get(physicalAncestor);
            Transform bindPhysical = gltfBindWorlds.get(physicalAncestor);
            Transform bindJoint = gltfBindWorlds.get(bone);
            if (physicalWorld == null || bindPhysical == null || bindJoint == null) continue;
            desired.put(bone, physicalWorld.copy().mul(bindPhysical.copy().invert()).mul(bindJoint));
        }
    }

    /** Original scale-free PMX path, kept exact to preserve its solver/writeback numerics. */
    private void collectRigidPhysicsPose(Bone bone, Frames.Pose parentWorld,
                                         Map<Bone, Frames.Pose> desired,
                                         Map<Bone, Transform> physicsPose) {
        Frames.Pose base = parentWorld == null
                ? Frames.compose(Frames.poseOf(skeleton.getTransform()), Frames.poseOf(bone.getTransform()))
                : Frames.compose(parentWorld, Frames.poseOf(bone.getTransform()));
        Frames.Pose local;
        Frames.Pose desiredWorld = desired.get(bone);
        if (desiredWorld != null) {
            local = Frames.relative(base, desiredWorld);
            Body body = bodyByBone.get(bone);
            boolean rotationOnly = bone != profileMotionRoot
                    && body != null && body.writeback == BoneWriteback.ROTATION_ONLY;
            if (rotationOnly) {
                Transform animationLocal = skeleton.getEvaluatedTransforms().get(bone);
                local.position.set(animationLocal == null
                        ? new Vector3f() : animationLocal.getPosition());
            }
            physicsPose.put(bone, Frames.transformOf(local));
        } else {
            Transform animationLocal = skeleton.getEvaluatedTransforms().get(bone);
            local = animationLocal == null ? new Frames.Pose() : Frames.poseOf(animationLocal);
        }
        Frames.Pose world = Frames.compose(base, local);
        if (bone.getChildren() == null) return;
        for (Bone child : bone.getChildren()) {
            if (child != null) collectRigidPhysicsPose(child, world, desired, physicsPose);
        }
    }

    private void collectAffinePhysicsPose(Bone bone, Transform parentWorld,
                                          Map<Bone, Transform> desired,
                                          Map<Bone, Transform> physicsPose) {
        // Keep the complete affine hierarchy here. Frames.Pose intentionally
        // carries only rigid TR data; using it for this conversion discarded
        // glTF root/node scale, so the local translation was solved in an
        // unscaled parent frame and then scaled again by SkeletonInstance.
        Transform base = parentWorld == null
                ? skeleton.getTransform().copy().mul(bone.getTransform())
                : parentWorld.copy().mul(bone.getTransform());
        Transform local;
        Transform desiredWorld = desired.get(bone);
        if (desiredWorld != null) {
            local = base.copy().invert().mul(desiredWorld.copy());
            Body body = bodyByBone.get(bone);
            boolean rotationOnly = bone != profileMotionRoot
                    && body != null && body.writeback == BoneWriteback.ROTATION_ONLY;
            if (rotationOnly) {
                Transform animationLocal = skeleton.getEvaluatedTransforms().get(bone);
                local.setPosition(animationLocal == null
                        ? new Vector3f() : animationLocal.getPosition());
            }
            physicsPose.put(bone, local);
        } else {
            Transform animationLocal = skeleton.getEvaluatedTransforms().get(bone);
            local = animationLocal == null ? new Transform() : animationLocal.copy();
        }
        Transform world = base.copy().mul(local);
        if (bone.getChildren() == null) return;
        for (Bone child : bone.getChildren()) {
            if (child != null) collectAffinePhysicsPose(child, world, desired, physicsPose);
        }
    }

    /** Replaces an animated world's rigid pose while retaining its scale/shear remainder. */
    private static Transform preserveAffine(Transform animatedWorld, Frames.Pose desiredWorld) {
        Matrix4f desiredRigid = new Matrix4f().translationRotate(
                desiredWorld.position, desiredWorld.rotation);
        if (animatedWorld == null) return new Transform().set(desiredRigid);
        Matrix4f animatedRigidInverse = new Matrix4f().translationRotate(
                animatedWorld.getPosition(), animatedWorld.getRotation()).invert();
        Matrix4f affineRemainder = animatedRigidInverse.mul(animatedWorld.transform());
        return new Transform().set(desiredRigid.mul(affineRemainder));
    }

    private Bone pmxBone(int pmxIndex) {
        if (pmxIndex < 0) return null;
        if (indexedBones != null) return indexedBones.get(pmxIndex);
        Bone[] bones = skeleton.getSkeleton().getBones();
        int skeletonIndex = pmxIndex + pmxBoneOffset;
        if (skeletonIndex < 0 || skeletonIndex >= bones.length) return null;
        Bone bone = bones[skeletonIndex];
        return bone.getBoneData() instanceof lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone
                ? bone : null;
    }

    private static List<PmxRigidBody> gltfBodyDefinitions(GltfModelData data) {
        int count = data.asset().nodes().size();
        List<PmxRigidBody> result = new ArrayList<>(count);
        for (int node = 0; node < count; node++) {
            String name = data.asset().nodes().names()[node];
            result.add(new PmxRigidBody(name, name, node,
                    0, 0, SimBody.SHAPE_CAPSULE,
                    new Vector3f(0.05f), new Vector3f(), new Vector3f(),
                    1f, 0f, 0f, 0f, 0.65f, 1));
        }
        return result;
    }

    private Vector3f scaled(Vector3f value) {
        return new Vector3f(value).mul(modelScale);
    }

    // ------------------------------------------------------------------
    // Topology construction
    // ------------------------------------------------------------------

    private List<Body> buildBodies(List<PmxRigidBody> definitions) {
        List<Body> result = new ArrayList<>(definitions.size());
        for (PmxRigidBody source : definitions) {
            result.add(buildBody(source));
        }
        return result;
    }

    private Body buildBody(PmxRigidBody source) {
        return buildBody(source, false);
    }

    private Body buildBody(PmxRigidBody source, boolean authoredSecondaryBody) {
        if (authoredSecondaryBody) {
            // Keep PMX's 16 authored collision layers, but move them away from
            // the generated humanoid's layers. Secondary chains still collide
            // with each other according to their original mask and with the
            // environment, while they cannot feed contact impulses back into
            // the primary capsules.
            source = new PmxRigidBody(source.localName(), source.universalName(), source.boneIndex(),
                    source.collisionGroup() + 16, source.nonCollisionMask() << 16,
                    source.shape(), source.size(), source.position(), source.rotation(), source.mass(),
                    Math.max(source.linearDamping(), 2f), Math.max(source.angularDamping(), 4f),
                    source.restitution(),
                    source.friction(), source.mode());
        }
        Bone bone = pmxBone(source.boneIndex());
        Frames.Pose pose = modelPose(scaled(source.position()), Frames.quaternionFromEuler(source.rotation()));
        Frames.Pose boneToBody = bone == null
                ? new Frames.Pose()
                : Frames.relative(Frames.poseOf(skeleton.getAbsoluteTransforms().get(bone)), pose);
        return new Body(source, bone, pose, boneToBody,
                source.mode() == 2 || authoredSecondaryBody
                        ? BoneWriteback.ROTATION_ONLY : BoneWriteback.FULL_POSE,
                new Vector3f(source.size()).mul(modelScale), false, false, authoredSecondaryBody);
    }

    private record RegisteredPhysics(List<Body> bodies, List<Joint> joints,
                                     Map<Integer, Body> bodyByDefinition,
                                     Bone motionRoot, Body rootBody) {}

    private RegisteredPhysics buildRegisteredPhysics(List<PmxRigidBody> definitions,
                                                      List<PmxJoint> authoredJoints,
                                                      Profile profile) {
        List<Body> registeredBodies = new ArrayList<>(profile.bodies.size());
        Map<Integer, Body> bodyByDefinition = new HashMap<>();
        Map<Body, Registration> registrationByBody = new IdentityHashMap<>();
        Map<Bone, Registration> registrationByBone = new IdentityHashMap<>();
        Map<Registration, PmxRigidBody> definitionByRegistration = new IdentityHashMap<>();

        for (Registration registration : profile.bodies) {
            if (registration.rigidBodyIndex < 0 || registration.rigidBodyIndex >= definitions.size()) {
                throw new IllegalArgumentException("registered rigid body index is outside PMX data: "
                        + registration.rigidBodyIndex);
            }
            PmxRigidBody authored = definitions.get(registration.rigidBodyIndex);
            if (bodyByDefinition.containsKey(registration.rigidBodyIndex)) {
                throw new IllegalArgumentException("duplicate registered rigid body index: "
                        + registration.rigidBodyIndex);
            }
            Bone bone = pmxBone(authored.boneIndex());
            if (bone == null) {
                throw new IllegalArgumentException("registered rigid body has no PMX bone: "
                        + registration.rigidBodyIndex);
            }
            if (registrationByBone.put(bone, registration) != null) {
                throw new IllegalArgumentException("multiple registered bodies use bone: " + bone.getName());
            }
            definitionByRegistration.put(registration, authored);
            // Reserve the authored index while the complete physical topology
            // is collected. Body construction happens in the second pass.
            bodyByDefinition.put(registration.rigidBodyIndex, null);
        }

        for (Registration registration : profile.bodies) {
            PmxRigidBody authored = definitionByRegistration.get(registration);
            Bone bone = pmxBone(authored.boneIndex());
            Transform boneTransform = skeleton.getAbsoluteTransforms().get(bone);
            Frames.Pose bonePose = Frames.poseOf(boneTransform);
            Vector3f start = new Vector3f(bonePose.position);
            Bone primaryChild = selectPrimaryPhysicalChild(bone, registration.role, registrationByBone);
            Vector3f end;
            if (primaryChild != null) {
                end = new Vector3f(skeleton.getAbsoluteTransforms().get(primaryChild).getPosition());
            } else {
                Bone parent = nearestRegisteredAncestor(bone, registrationByBone);
                if (parent == null) {
                    end = new Vector3f(start).add(0f, 0.08f, 0f);
                } else {
                    Vector3f parentPosition = skeleton.getAbsoluteTransforms().get(parent).getPosition();
                    Vector3f direction = new Vector3f(start).sub(parentPosition);
                    float parentLength = direction.length();
                    if (parentLength > Frames.EPSILON) direction.div(parentLength);
                    else direction.set(0f, 1f, 0f);
                    end = new Vector3f(start).fma(Math.max(parentLength * 0.55f, 0.045f), direction);
                }
            }
            Vector3f axis = new Vector3f(end).sub(start);
            float length = axis.length();
            if (length <= Frames.EPSILON) {
                axis.set(0f, 1f, 0f);
                length = 0.08f;
                end.set(start).fma(length, axis);
            } else {
                axis.div(length);
            }
            Vector3f center = new Vector3f(start).add(end).mul(0.5f);
            Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0f, 1f, 0f), axis);
            // PMX bone positions have already been converted into world units
            // before the skeleton is built. glTF keeps its import scale as
            // model metadata, so only that path still needs the radius scaled.
            float profileScale = indexedBones == null ? 1f : uniformScale(modelScale);
            float radius = Math.min(profileRadius(registration.role) * profileScale,
                    Math.max(0.026f * profileScale, length * 0.32f));
            float mass = profileMass(registration.role, length, radius);

            // Primary humanoid bodies are generated from the actual skeleton
            // segment. PMX rigid bodies are authored
            // mostly for secondary motion and are often poor human colliders.
            PmxRigidBody dynamic = new PmxRigidBody(
                    authored.localName(), authored.universalName(), authored.boneIndex(),
                    authored.collisionGroup(), authored.nonCollisionMask() | 0xffff0000, 2,
                    authored.size(), authored.position(), authored.rotation(),
                    mass, 0f, 0f,
                    authored.restitution(), authored.friction(), 1);
            Frames.Pose bodyPose = new Frames.Pose(center, rotation);
            Body body = new Body(dynamic, bone, bodyPose,
                    Frames.relative(bonePose, bodyPose), BoneWriteback.FULL_POSE,
                    new Vector3f(radius, length, 0f), true, true);
            registeredBodies.add(body);
            bodyByDefinition.put(registration.rigidBodyIndex, body);
            registrationByBody.put(body, registration);
        }

        List<Joint> registeredJoints = new ArrayList<>(Math.max(0, registeredBodies.size() - 1));
        Map<Bone, Body> registeredBodyByBone = new IdentityHashMap<>();
        for (Body body : registeredBodies) registeredBodyByBone.put(body.bone, body);
        for (Body child : registeredBodies) {
            Registration childRegistration = registrationByBody.get(child);
            Body parent = nearestPhysicalAncestor(child.bone, registeredBodyByBone);
            // PMX humanoids sometimes split the upper and lower body below an
            // unregistered waist helper. Keep the authored parent as an
            // explicit bridge only when the skeleton has no physical ancestor.
            if (parent == null && childRegistration.parentRigidBodyIndex >= 0) {
                parent = bodyByDefinition.get(childRegistration.parentRigidBodyIndex);
                if (parent == null) {
                    throw new IllegalArgumentException("registered parent rigid body is missing: "
                            + childRegistration.parentRigidBodyIndex);
                }
            }
            if (parent == null) continue;
            Vector3f anchor = child.bone.getBoneData()
                    instanceof lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone pmx
                    ? new Vector3f(pmx.position) : new Vector3f();
            Vector3f minimum = childRegistration.rotationMinimum();
            Vector3f maximum = childRegistration.rotationMaximum();
            PmxJoint source = new PmxJoint(
                    child.bone.getName(), child.bone.getName(), 0,
                    registeredBodies.indexOf(parent), registeredBodies.indexOf(child),
                    anchor, new Vector3f(), new Vector3f(), new Vector3f(),
                    minimum, maximum, new Vector3f(), new Vector3f());
            Frames.Pose worldFrame = Frames.poseOf(skeleton.getAbsoluteTransforms().get(child.bone));
            Vector3f twistAxis = child.pose.rotation.transform(new Vector3f(0f, 1f, 0f));
            new Quaternionf(worldFrame.rotation).invert().transform(twistAxis).normalize();
            registeredJoints.add(new Joint(source, parent, child,
                    Frames.relative(parent.pose, worldFrame), Frames.relative(child.pose, worldFrame),
                    new Vector3f(), new Vector3f(), minimum, maximum,
                    new Vector3f(), new Vector3f(),
                    childRegistration.swingTwistLimit(), twistAxis));
        }
        Set<Body> childBodies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Joint joint : registeredJoints) childBodies.add((Body) joint.bodyB());
        Body rootBody = null;
        for (Body body : registeredBodies) {
            if (!childBodies.contains(body)) {
                if (rootBody != null) {
                    throw new IllegalArgumentException("profile must contain exactly one root body");
                }
                rootBody = body;
            }
        }
        if (rootBody == null) throw new IllegalArgumentException("profile must contain a root body");
        // Global ragdoll motion belongs at the skeleton root. Applying it to
        // an internal common ancestor ("腰" in the active PMX) visibly stretches
        // the edge from that ancestor to the model's center/root bones.
        Bone motionRoot = skeleton.getSkeleton().getRoot();

        // A humanoid profile replaces only its explicitly registered primary
        // bodies. PMX skirt/hair/accessory chains retain their authored shapes,
        // damping and joints when secondary motion is requested. glTF has no
        // PMX rigid-body table, so its generated node placeholders are never
        // treated as authored secondary physics.
        if (profile.includeSecondaryBodies && indexedBones == null) {
            Set<Bone> primaryBones = Collections.newSetFromMap(new IdentityHashMap<>());
            primaryBones.addAll(registrationByBone.keySet());
            for (int index = 0; index < definitions.size(); index++) {
                if (bodyByDefinition.containsKey(index)) continue;
                PmxRigidBody definition = definitions.get(index);
                Bone bone = pmxBone(definition.boneIndex());
                if (bone != null && primaryBones.contains(bone)) continue;
                Body secondary = buildBody(definition, true);
                registeredBodies.add(secondary);
                bodyByDefinition.put(index, secondary);
            }
            for (PmxJoint authoredJoint : authoredJoints) {
                Body a = bodyByDefinition.get(authoredJoint.rigidBodyA());
                Body b = bodyByDefinition.get(authoredJoint.rigidBodyB());
                if (a == null || b == null) continue;
                boolean bothPrimary = a.profiledRagdollBody && b.profiledRagdollBody;
                if (bothPrimary) continue;
                if (a.profiledRagdollBody) {
                    a = secondaryAnchor(definitions.get(authoredJoint.rigidBodyA()), a,
                            registeredBodies);
                }
                if (b.profiledRagdollBody) {
                    b = secondaryAnchor(definitions.get(authoredJoint.rigidBodyB()), b,
                            registeredBodies);
                }
                registeredJoints.add(buildJoint(authoredJoint, a, b));
            }
        }
        return new RegisteredPhysics(registeredBodies, registeredJoints,
                Map.copyOf(bodyByDefinition), motionRoot, rootBody);
    }

    private Body secondaryAnchor(PmxRigidBody authored, Body primary, List<Body> allBodies) {
        for (Body candidate : allBodies) {
            if (candidate.secondaryAnchorBody && candidate.kinematicFollowBody == primary) return candidate;
        }
        Frames.Pose pose = modelPose(scaled(authored.position()),
                Frames.quaternionFromEuler(authored.rotation()));
        PmxRigidBody source = new PmxRigidBody(
                authored.localName() + "#secondary-anchor",
                authored.universalName() + "#secondary-anchor", authored.boneIndex(),
                63, -1, SimBody.SHAPE_SPHERE,
                new Vector3f(1.0e-4f), authored.position(), authored.rotation(),
                0f, 0f, 0f, 0f, 0f, 0);
        Body anchor = new Body(source, null, pose, new Frames.Pose(), BoneWriteback.FULL_POSE,
                new Vector3f(1.0e-4f), false, false, true);
        anchor.secondaryAnchorBody = true;
        anchor.kinematicFollowBody = primary;
        anchor.kinematicFollowOffset = Frames.relative(primary.pose, pose);
        primary.kinematicFollowers.add(anchor);
        allBodies.add(anchor);
        return anchor;
    }

    private Bone selectPrimaryPhysicalChild(Bone parent, BodyRole parentRole,
                                            Map<Bone, Registration> registrationByBone) {
        Bone bestPreferred = null;
        Bone bestFallback = null;
        float preferredDistance = -1f;
        float fallbackDistance = -1f;
        Vector3f parentPosition = skeleton.getAbsoluteTransforms().get(parent).getPosition();
        for (Map.Entry<Bone, Registration> entry : registrationByBone.entrySet()) {
            Bone child = entry.getKey();
            if (nearestRegisteredAncestor(child, registrationByBone) != parent) continue;
            float distance = parentPosition.distanceSquared(
                    skeleton.getAbsoluteTransforms().get(child).getPosition());
            if (distance > fallbackDistance) {
                fallbackDistance = distance;
                bestFallback = child;
            }
            if (continues(parentRole, entry.getValue().role) && distance > preferredDistance) {
                preferredDistance = distance;
                bestPreferred = child;
            }
        }
        return bestPreferred != null ? bestPreferred : bestFallback;
    }

    private static Bone nearestRegisteredAncestor(Bone bone,
                                                  Map<Bone, Registration> registrationByBone) {
        for (Bone current = bone.getParent(); current != null; current = current.getParent()) {
            if (registrationByBone.containsKey(current)) return current;
        }
        return null;
    }

    private static boolean continues(BodyRole parent, BodyRole child) {
        return switch (parent) {
            case PELVIS -> child == BodyRole.SPINE || child == BodyRole.CHEST;
            case SPINE -> child == BodyRole.SPINE || child == BodyRole.CHEST || child == BodyRole.NECK;
            case CHEST -> child == BodyRole.NECK || child == BodyRole.HEAD;
            case NECK -> child == BodyRole.HEAD;
            case SHOULDER -> child == BodyRole.UPPER_ARM;
            case UPPER_ARM -> child == BodyRole.LOWER_ARM;
            case LOWER_ARM -> child == BodyRole.HAND;
            case UPPER_LEG -> child == BodyRole.LOWER_LEG;
            case LOWER_LEG -> child == BodyRole.FOOT;
            case FOOT -> child == BodyRole.TOE;
            default -> false;
        };
    }

    private static float profileRadius(BodyRole role) {
        return switch (role) {
            case HEAD -> 0.105f;
            case PELVIS, SPINE, CHEST -> 0.09f;
            case UPPER_LEG -> 0.065f;
            case LOWER_LEG -> 0.055f;
            case FOOT -> 0.05f;
            case TOE -> 0.035f;
            case SHOULDER, UPPER_ARM -> 0.05f;
            case LOWER_ARM -> 0.045f;
            case HAND -> 0.048f;
            case NECK -> 0.052f;
        };
    }

    private static float uniformScale(Vector3f scale) {
        return (float)Math.cbrt(Math.abs((double)scale.x * scale.y * scale.z));
    }

    private static float profileMass(BodyRole role, float length, float radius) {
        float anatomicalScale = switch (role) {
            case PELVIS, SPINE, CHEST -> 4.5f;
            case HEAD -> 2.2f;
            case UPPER_LEG -> 2.8f;
            case LOWER_LEG -> 1.8f;
            case UPPER_ARM -> 1.5f;
            default -> 1f;
        };
        return Math.max(0.2f, anatomicalScale * Math.max(0.08f, length + radius * 2f));
    }

    private static Body nearestPhysicalAncestor(Bone bone, Map<Bone, Body> registeredBodyByBone) {
        for (Bone current = bone.getParent(); current != null; current = current.getParent()) {
            Body body = registeredBodyByBone.get(current);
            if (body != null) return body;
        }
        return null;
    }

    private List<Joint> buildJoints(List<PmxJoint> definitions) {
        List<Joint> result = new ArrayList<>(definitions.size());
        for (PmxJoint source : definitions) {
            if (source.rigidBodyA() < 0 || source.rigidBodyA() >= bodies.size()
                    || source.rigidBodyB() < 0 || source.rigidBodyB() >= bodies.size()) continue;
            Body a = bodies.get(source.rigidBodyA());
            Body b = bodies.get(source.rigidBodyB());
            result.add(buildJoint(source, a, b));
        }
        return result;
    }

    private Joint buildJoint(PmxJoint source, Body a, Body b) {
        Frames.Pose worldFrame = modelPose(scaled(source.position()),
                Frames.quaternionFromEuler(source.rotation()));
        return new Joint(source, a, b,
                Frames.relative(a.pose, worldFrame), Frames.relative(b.pose, worldFrame),
                scaled(source.positionMin()), scaled(source.positionMax()),
                new Vector3f(source.rotationMin()), new Vector3f(source.rotationMax()),
                new Vector3f(source.positionSpring()), new Vector3f(source.rotationSpring()),
                null, new Vector3f(0f, 1f, 0f));
    }

    // ------------------------------------------------------------------
    // Configuration records
    // ------------------------------------------------------------------

    /** Semantic primary-body roles used by explicit humanoid registrations. */
    public enum BodyRole {
        PELVIS(30f, 35f, 30f),
        SPINE(25f, 30f, 25f),
        CHEST(30f, 35f, 30f),
        NECK(35f, 45f, 35f),
        HEAD(40f, 55f, 40f),
        SHOULDER(55f, 55f, 55f),
        UPPER_ARM(110f, 90f, 110f),
        LOWER_ARM(100f, 20f, 20f),
        HAND(45f, 45f, 45f),
        UPPER_LEG(100f, 50f, 50f),
        LOWER_LEG(110f, 15f, 15f),
        FOOT(40f, 35f, 35f),
        TOE(20f, 7f, 7f);

        private final Vector3f extent;

        BodyRole(float xDegrees, float yDegrees, float zDegrees) {
            this.extent = new Vector3f((float) Math.toRadians(xDegrees),
                    (float) Math.toRadians(yDegrees), (float) Math.toRadians(zDegrees));
        }

        Vector3f rotationMinimum() { return new Vector3f(extent).negate(); }
        Vector3f rotationMaximum() { return new Vector3f(extent); }
        SwingTwistLimit swingTwistLimit() {
            return new SwingTwistLimit(Math.max(extent.x, extent.z), -extent.y, extent.y, 0.58f);
        }
    }

    /** Cone swing plus signed axial twist limit, expressed in radians. */
    public record SwingTwistLimit(float maxSwing, float minTwist, float maxTwist,
                                  float stiffness) implements BallJoint.RotationLimiter {
        public SwingTwistLimit {
            if (!Float.isFinite(maxSwing) || maxSwing < 0f || maxSwing > Math.PI
                    || !Float.isFinite(minTwist) || !Float.isFinite(maxTwist)
                    || minTwist < -Math.PI || maxTwist > Math.PI || minTwist > maxTwist
                    || !Float.isFinite(stiffness) || stiffness < 0f || stiffness > 1f) {
                throw new IllegalArgumentException("invalid swing/twist limit");
            }
        }

        @Override
        public Quaternionf clamp(Quaternionf relativeRotation, Vector3f twistAxis) {
            Quaternionf deviation = Frames.canonical(relativeRotation);
            Quaternionf twist = Frames.decomposeTwist(deviation, twistAxis);
            Quaternionf swing = Frames.canonical(new Quaternionf(deviation)
                    .mul(new Quaternionf(twist).invert()));
            float swingAngle = Frames.quaternionAngle(swing);
            if (swingAngle > maxSwing) {
                Vector3f axis = Frames.quaternionAxis(swing);
                swing.rotationAxis(maxSwing, axis);
            }
            float clampedTwist = Math.clamp(Frames.signedTwistAngle(twist, twistAxis),
                    minTwist, maxTwist);
            return Frames.canonical(swing.mul(new Quaternionf().rotationAxis(clampedTwist, twistAxis)));
        }

        @Override
        public float violation(Quaternionf relativeRotation, Vector3f twistAxis) {
            Quaternionf canonical = Frames.canonical(relativeRotation);
            Quaternionf twist = Frames.decomposeTwist(canonical, twistAxis);
            Quaternionf swing = Frames.canonical(new Quaternionf(canonical)
                    .mul(new Quaternionf(twist).invert()));
            float twistAngle = Frames.signedTwistAngle(twist, twistAxis);
            return Math.max(Math.max(0f, Frames.quaternionAngle(swing) - maxSwing),
                    Math.max(minTwist - twistAngle, twistAngle - maxTwist));
        }

        @Override public float box3dConeAngle() { return maxSwing; }
        @Override public float box3dLowerTwistAngle() { return minTwist; }
        @Override public float box3dUpperTwistAngle() { return maxTwist; }

        @Override
        public float stiffness() {
            return stiffness;
        }
    }

    /** One explicitly authored primary humanoid body. */
    public record Registration(int rigidBodyIndex, int parentRigidBodyIndex, BodyRole role,
                               Vector3f rotationMinimum, Vector3f rotationMaximum,
                               SwingTwistLimit swingTwistLimit) {
        public Registration {
            if (rigidBodyIndex < 0) throw new IllegalArgumentException("rigidBodyIndex must be non-negative");
            if (parentRigidBodyIndex < -1 || parentRigidBodyIndex == rigidBodyIndex) {
                throw new IllegalArgumentException("parentRigidBodyIndex must be -1 or a different body");
            }
            Objects.requireNonNull(role, "role");
            rotationMinimum = new Vector3f(Objects.requireNonNull(rotationMinimum, "rotationMinimum"));
            rotationMaximum = new Vector3f(Objects.requireNonNull(rotationMaximum, "rotationMaximum"));
            if (!rotationMinimum.isFinite() || !rotationMaximum.isFinite()) {
                throw new IllegalArgumentException("rotation limits must be finite");
            }
        }

        public Registration(int rigidBodyIndex, int parentRigidBodyIndex, BodyRole role) {
            this(rigidBodyIndex, parentRigidBodyIndex, role,
                    role.rotationMinimum(), role.rotationMaximum(), role.swingTwistLimit());
        }

        /** Backwards-compatible authored Euler-box limit. */
        public Registration(int rigidBodyIndex, int parentRigidBodyIndex, BodyRole role,
                            Vector3f rotationMinimum, Vector3f rotationMaximum) {
            this(rigidBodyIndex, parentRigidBodyIndex, role,
                    rotationMinimum, rotationMaximum, null);
        }

        public Registration(int rigidBodyIndex, int parentRigidBodyIndex, BodyRole role,
                            SwingTwistLimit swingTwistLimit) {
            this(rigidBodyIndex, parentRigidBodyIndex, role,
                    role.rotationMinimum(), role.rotationMaximum(),
                    Objects.requireNonNull(swingTwistLimit, "swingTwistLimit"));
        }

        public Registration(int rigidBodyIndex, BodyRole role) {
            this(rigidBodyIndex, -1, role);
        }

        @Override public Vector3f rotationMinimum() { return new Vector3f(rotationMinimum); }
        @Override public Vector3f rotationMaximum() { return new Vector3f(rotationMaximum); }
    }

    /**
     * Explicit main-ragdoll selection. PMX profiles may additionally retain
     * every unregistered authored body/joint as secondary cloth, hair and
     * accessory motion. glTF profiles ignore that flag because glTF itself
     * does not define rigid-body physics.
     */
    public record Profile(List<Registration> bodies, boolean includeSecondaryBodies) {
        public Profile {
            bodies = List.copyOf(Objects.requireNonNull(bodies, "bodies"));
            if (bodies.isEmpty()) throw new IllegalArgumentException("profile must register at least one body");
        }

        public Profile(List<Registration> bodies) {
            this(bodies, false);
        }

        public static Profile of(Registration... bodies) {
            return new Profile(List.of(bodies));
        }

        public Profile withSecondaryBodies() {
            return includeSecondaryBodies ? this : new Profile(bodies, true);
        }
    }

    private enum BoneWriteback {
        FULL_POSE,
        ROTATION_ONLY
    }

    // ------------------------------------------------------------------
    // Body and joint views
    // ------------------------------------------------------------------

    /** One simulated rigid body backed by an authored PMX or generated profile shape. */
    public static final class Body implements SimBody {
        private final PmxRigidBody source;
        private final Bone bone;
        private final Frames.Pose pose;
        private final Frames.Pose previousPose;
        private final Frames.Pose interpolationPose;
        private final Frames.Pose boneToBody;
        private final BoneWriteback writeback;
        private final Vector3f shapeSize;
        private final boolean profiledRagdollBody;
        private final boolean ragdollAlignmentSpring;
        private final boolean authoredSecondaryBody;
        private int selfCollisionGroup;
        private boolean secondaryAnchorBody;
        private Body kinematicFollowBody;
        private Frames.Pose kinematicFollowOffset;
        private final List<Body> kinematicFollowers = new ArrayList<>();
        private Runnable wake = () -> {};
        private final float inverseLinearMass;
        private final Vector3f linearVelocity = new Vector3f();
        private final Vector3f angularVelocity = new Vector3f();
        /** Kinematic/force target captured by the last {@code evaluateAnimationTarget()}; reused storage. */
        private final Frames.Pose animationTargetCache = new Frames.Pose();

        private Body(PmxRigidBody source, Bone bone, Frames.Pose pose, Frames.Pose boneToBody,
                     BoneWriteback writeback, Vector3f shapeSize, boolean profiledRagdollBody) {
            this(source, bone, pose, boneToBody, writeback, shapeSize,
                    profiledRagdollBody, false, false);
        }

        private Body(PmxRigidBody source, Bone bone, Frames.Pose pose, Frames.Pose boneToBody,
                     BoneWriteback writeback, Vector3f shapeSize, boolean profiledRagdollBody,
                     boolean ragdollAlignmentSpring) {
            this(source, bone, pose, boneToBody, writeback, shapeSize,
                    profiledRagdollBody, ragdollAlignmentSpring, false);
        }

        private Body(PmxRigidBody source, Bone bone, Frames.Pose pose, Frames.Pose boneToBody,
                     BoneWriteback writeback, Vector3f shapeSize, boolean profiledRagdollBody,
                     boolean ragdollAlignmentSpring, boolean authoredSecondaryBody) {
            this.source = source;
            this.bone = bone;
            this.pose = pose;
            this.previousPose = new Frames.Pose().set(pose);
            this.interpolationPose = new Frames.Pose().set(pose);
            this.boneToBody = boneToBody;
            this.writeback = writeback;
            this.shapeSize = shapeSize;
            this.profiledRagdollBody = profiledRagdollBody;
            this.ragdollAlignmentSpring = ragdollAlignmentSpring;
            this.authoredSecondaryBody = authoredSecondaryBody;
            float mass = source.mass();
            float inverseMass = mass > 1e-7f ? 1f / mass : 0f;
            this.inverseLinearMass = source.mode() == 0 ? 0f : inverseMass;
        }

        public PmxRigidBody source() { return source; }
        public Bone bone() { return bone; }
        public Vector3f position() { return new Vector3f(pose.position); }
        public Quaternionf rotation() { return new Quaternionf(pose.rotation); }
        public Vector3f shapeSize() { return new Vector3f(shapeSize); }
        public Vector3f linearVelocity() { return new Vector3f(linearVelocityRef()); }
        public Vector3f angularVelocity() { return new Vector3f(angularVelocityRef()); }

        public Vector3f toWorldPoint(Vector3f localPoint) {
            return pose.rotation.transform(new Vector3f(localPoint)).add(pose.position);
        }

        public Vector3f toLocalPoint(Vector3f worldPoint) {
            return new Quaternionf(pose.rotation).invert()
                    .transform(new Vector3f(worldPoint).sub(pose.position));
        }

        public void teleport(Vector3f position, Quaternionf rotation) {
            wake.run();
            teleportPose(new Frames.Pose(Objects.requireNonNull(position, "position"),
                    Objects.requireNonNull(rotation, "rotation")));
            for (Body follower : kinematicFollowers) {
                follower.teleportPose(Frames.compose(pose, follower.kinematicFollowOffset));
            }
        }

        private void teleportPose(Frames.Pose target) {
            pose.set(target);
            previousPose.set(pose);
            interpolationPose.set(pose);
            linearVelocity.zero();
            angularVelocity.zero();
        }

        public void setLinearVelocity(Vector3f velocity) {
            wake.run();
            linearVelocity.set(Objects.requireNonNull(velocity, "velocity"));
        }

        public void setAngularVelocity(Vector3f velocity) {
            wake.run();
            angularVelocity.set(Objects.requireNonNull(velocity, "velocity"));
        }

        @Override public int shape() { return source.shape(); }
        @Override public Vector3f shapeSizeRef() { return shapeSize; }
        @Override public float friction() { return source.friction(); }
        @Override public float restitution() { return source.restitution(); }
        @Override public float rollingResistance() { return profiledRagdollBody ? 0.2f : 0f; }
        @Override public boolean profiledRagdollBody() { return profiledRagdollBody; }
        @Override public boolean authoredSecondaryBody() { return authoredSecondaryBody; }
        @Override public int selfCollisionGroup() { return selfCollisionGroup; }
        public boolean secondaryAnchorBody() { return secondaryAnchorBody; }
        @Override public boolean ragdollAlignmentSpring() { return ragdollAlignmentSpring; }
        @Override public int collisionGroup() { return source.collisionGroup(); }
        @Override public int nonCollisionMask() { return source.nonCollisionMask(); }
        @Override public boolean kinematic() { return source.mode() == 0; }
        @Override public float linearDamping() { return source.linearDamping(); }
        @Override public float angularDamping() { return source.angularDamping(); }
        @Override public float inverseLinearMass() { return inverseLinearMass; }

        @Override public Vector3f positionRef() { return pose.position; }
        @Override public Vector3f previousPositionRef() { return previousPose.position; }
        @Override public Quaternionf rotationRef() { return pose.rotation; }
        @Override public Quaternionf previousRotationRef() { return previousPose.rotation; }
        @Override public Vector3f interpolationPositionRef() { return interpolationPose.position; }
        @Override public Quaternionf interpolationRotationRef() { return interpolationPose.rotation; }
        @Override public Vector3f linearVelocityRef() { return linearVelocity; }
        @Override public Vector3f angularVelocityRef() { return angularVelocity; }

        void wireWake(Runnable wakeCallback) {
            this.wake = wakeCallback;
        }
    }

    /** Joint view keeping the authored PMX definition alongside its Box3D mapping. */
    public static final class Joint extends BallJoint {
        private final PmxJoint source;

        @SuppressWarnings("NullAway")
        Joint(PmxJoint source, SimBody bodyA, SimBody bodyB, Frames.Pose localA, Frames.Pose localB,
              Vector3f positionMin, Vector3f positionMax,
              Vector3f rotationMinimum, Vector3f rotationMaximum,
              Vector3f springLinear, Vector3f springAngular,
              BallJoint.RotationLimiter rotationLimiter, Vector3f twistAxis) {
            super(bodyA, bodyB, localA, localB, positionMin, positionMax,
                    rotationMinimum, rotationMaximum, springLinear, springAngular,
                    rotationLimiter, twistAxis);
            this.source = source;
        }

        /** The authored PMX joint, or null for synthesized profile joints. */
        public PmxJoint source() { return source; }
    }
}
