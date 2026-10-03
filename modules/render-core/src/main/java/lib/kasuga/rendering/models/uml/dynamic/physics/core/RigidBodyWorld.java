package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Java ownership and fixed-step adapter for the native Box3D world.
 *
 * <p>This class intentionally contains no integrator, collision detection or
 * constraint solver. Java objects are configuration/state mirrors; Box3D is
 * the sole simulation engine.</p>
 */
public final class RigidBodyWorld implements AutoCloseable {
    private static final float EARTH_GRAVITY = 9.80665f;
    private static final float DEFAULT_SIMULATION_HERTZ = 120f;
    private static final int DEFAULT_MAX_FIXED_STEPS_PER_UPDATE = 12;
    private static final float DEFAULT_CONSTRAINT_HERTZ = 60f;
    private static final float DEFAULT_CONSTRAINT_DAMPING_RATIO = 2f;
    private static final float DEFAULT_MAX_LINEAR_SPEED = 100f;
    private static final float DEFAULT_MAX_ANGULAR_SPEED = 50f;

    public static final int DEFAULT_SUBSTEP_COUNT = 4;
    public static final int PROFILE_SUBSTEP_COUNT = 4;

    public interface KinematicDriver {
        void beginStep(RigidBodyWorld world);
        Pose kinematicTarget(SimBody body);

        static KinematicDriver none() {
            return new KinematicDriver() {
                @Override public void beginStep(RigidBodyWorld world) {}
                @Override public Pose kinematicTarget(SimBody body) {
                    return new Pose(body.positionRef(), body.rotationRef());
                }
            };
        }
    }

    private final List<SimBody> bodies = new ArrayList<>();
    private final List<BallJoint> joints = new ArrayList<>();
    private final List<PhysicsJoint> physicsJoints = new ArrayList<>();
    private final Box3DBackend backend;
    private final Vector3f gravity = new Vector3f(0f, -EARTH_GRAVITY, 0f);
    private final Vector3d worldOrigin = new Vector3d();

    private int solverIterations = 1;
    private float simulationHertz = DEFAULT_SIMULATION_HERTZ;
    private int substepCount;
    private float constraintHertz = DEFAULT_CONSTRAINT_HERTZ;
    private float constraintDampingRatio = DEFAULT_CONSTRAINT_DAMPING_RATIO;
    private float maxLinearSpeed = DEFAULT_MAX_LINEAR_SPEED;
    private float maxAngularSpeed = DEFAULT_MAX_ANGULAR_SPEED;
    private int maxFixedStepsPerUpdate = DEFAULT_MAX_FIXED_STEPS_PER_UPDATE;
    private float accumulatedTime;
    private int lastFixedStepCount;
    private float droppedSimulationTime;
    private boolean sleepingEnabled = true;
    private boolean continuousCollisionEnabled = true;
    private boolean collisionsEnabled = true;
    private boolean selfCollisionsEnabled = true;
    private float restitutionThreshold = 1f;
    private boolean sleeping;
    private float sleepTime;
    private CollisionEnvironment collisionEnvironment;
    private DragSettings dragSettings = DragSettings.DEFAULT;
    private SimBody draggedBody;
    private boolean closed;

    public RigidBodyWorld(List<? extends SimBody> bodies,
                          List<? extends BallJoint> joints,
                          int substepCount) {
        if (substepCount < 1 || substepCount > 50) {
            throw new IllegalArgumentException("substepCount must be within [1, 50]");
        }
        this.substepCount = substepCount;
        this.bodies.addAll(Objects.requireNonNull(bodies, "bodies"));
        this.joints.addAll(Objects.requireNonNull(joints, "joints"));
        backend = new Box3DBackend(gravity, sleepingEnabled, continuousCollisionEnabled);
        backend.setRestitutionThreshold(restitutionThreshold);
        backend.setMaximumLinearSpeed(maxLinearSpeed);
        for (SimBody body : this.bodies) backend.addBody(body);
        for (BallJoint joint : this.joints) backend.addJoint(joint);
    }

    public boolean add(SimBody body) {
        ensureOpen();
        Objects.requireNonNull(body, "body");
        if (bodies.contains(body)) return false;
        bodies.add(body);
        backend.addBody(body);
        return true;
    }

    public boolean remove(SimBody body) {
        ensureOpen();
        if (!bodies.remove(Objects.requireNonNull(body, "body"))) return false;
        if (draggedBody == body) endDrag();
        List<BallJoint> attachedJoints = joints.stream()
                .filter(joint -> joint.bodyA() == body || joint.bodyB() == body)
                .toList();
        for (BallJoint joint : attachedJoints) remove(joint);
        List<PhysicsJoint> attachedPhysicsJoints = physicsJoints.stream()
                .filter(joint -> joint.bodyA() == body || joint.bodyB() == body)
                .toList();
        for (PhysicsJoint joint : attachedPhysicsJoints) remove(joint);
        backend.removeBody(body);
        return true;
    }

    public void add(BallJoint joint) {
        ensureOpen();
        BallJoint value = Objects.requireNonNull(joint, "joint");
        if (!bodies.contains(value.bodyA()) || !bodies.contains(value.bodyB())) {
            throw new IllegalArgumentException("joint bodies must belong to this world");
        }
        joints.add(value);
        backend.addJoint(value);
    }

    public void remove(BallJoint joint) {
        ensureOpen();
        BallJoint value = Objects.requireNonNull(joint, "joint");
        if (joints.remove(value)) backend.removeJoint(value);
    }

    /** Registers a weld/distance/revolute/prismatic joint between two world bodies. */
    public void add(PhysicsJoint joint) {
        ensureOpen();
        PhysicsJoint value = Objects.requireNonNull(joint, "joint");
        if (physicsJoints.contains(value)) return;
        if (!bodies.contains(value.bodyA()) || !bodies.contains(value.bodyB())) {
            throw new IllegalArgumentException("joint bodies must belong to this world");
        }
        physicsJoints.add(value);
        backend.addJoint(value);
    }

    public void remove(PhysicsJoint joint) {
        ensureOpen();
        PhysicsJoint value = Objects.requireNonNull(joint, "joint");
        if (physicsJoints.remove(value)) backend.removeJoint(value);
    }

    public List<SimBody> bodies() { return Collections.unmodifiableList(bodies); }
    public List<BallJoint> joints() { return Collections.unmodifiableList(joints); }
    public List<PhysicsJoint> physicsJoints() { return Collections.unmodifiableList(physicsJoints); }

    /** High-precision anchor added to every local Box3D position. */
    public Vector3d worldOrigin() { return new Vector3d(worldOrigin); }

    public void setWorldOrigin(Vector3dc origin) {
        Objects.requireNonNull(origin, "origin");
        if (!Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z())) {
            throw new IllegalArgumentException("world origin must be finite");
        }
        worldOrigin.set(origin);
    }

    public Vector3f worldToLocal(double x, double y, double z) {
        return new Vector3f((float) (x - worldOrigin.x),
                (float) (y - worldOrigin.y), (float) (z - worldOrigin.z));
    }

    public Vector3d localToWorld(Vector3f local) {
        Objects.requireNonNull(local, "local");
        return new Vector3d(worldOrigin).add(local.x, local.y, local.z);
    }

    public boolean applyImpulse(SimBody body, Vector3f impulse) {
        if (!ownsDynamicBody(body) || impulse == null || !impulse.isFinite()) return false;
        return backend.applyImpulse(body, impulse, null);
    }

    public boolean applyImpulse(SimBody body, Vector3f impulse, Vector3f worldPoint) {
        if (!ownsDynamicBody(body) || impulse == null || !impulse.isFinite()
                || worldPoint == null || !worldPoint.isFinite()) return false;
        return backend.applyImpulse(body, impulse, worldPoint);
    }

    public boolean applyAngularImpulse(SimBody body, Vector3f impulse) {
        return ownsDynamicBody(body) && impulse != null && impulse.isFinite()
                && backend.applyAngularImpulse(body, impulse);
    }

    /** Applies a continuous world-space force at the center of mass for the next Box3D step. */
    public boolean applyForce(SimBody body, Vector3f force) {
        return ownsDynamicBody(body) && force != null && force.isFinite()
                && backend.applyForce(body, force, null);
    }

    /** Applies a continuous world-space force at a point, including its torque. */
    public boolean applyForce(SimBody body, Vector3f force, Vector3f worldPoint) {
        return ownsDynamicBody(body) && force != null && force.isFinite()
                && worldPoint != null && worldPoint.isFinite()
                && backend.applyForce(body, force, worldPoint);
    }

    /** Applies a continuous world-space torque for the next Box3D step. */
    public boolean applyTorque(SimBody body, Vector3f torque) {
        return ownsDynamicBody(body) && torque != null && torque.isFinite()
                && backend.applyTorque(body, torque);
    }

    /** Sets Box3D's per-body gravity multiplier. Negative values invert gravity. */
    public boolean setGravityScale(SimBody body, float scale) {
        return ownsDynamicBody(body) && Float.isFinite(scale)
                && backend.setGravityScale(body, scale);
    }

    /** Returns the native gravity multiplier, or NaN when the body is not owned. */
    public float gravityScale(SimBody body) {
        return body != null && bodies.contains(body) ? backend.gravityScale(body) : Float.NaN;
    }

    /** Wakes or sleeps one dynamic Box3D body. */
    public boolean setAwake(SimBody body, boolean awake) {
        return ownsDynamicBody(body) && backend.setAwake(body, awake);
    }

    public boolean awake(SimBody body) {
        return body != null && bodies.contains(body) && backend.awake(body);
    }

    /** Enables or completely removes one body from native simulation without destroying it or its joints. */
    public boolean setBodyEnabled(SimBody body, boolean enabled) {
        return body != null && bodies.contains(body) && backend.setBodyEnabled(body, enabled);
    }

    public boolean bodyEnabled(SimBody body) {
        return body != null && bodies.contains(body) && backend.bodyEnabled(body);
    }

    private boolean ownsDynamicBody(SimBody body) {
        return body != null && bodies.contains(body) && backend.bodyEnabled(body)
                && body.inverseLinearMass() > 0f;
    }

    public java.util.Optional<RayHit> raycast(Vector3f origin, Vector3f direction,
                                              float maximumDistance) {
        Objects.requireNonNull(origin, "origin");
        Vector3f normalized = new Vector3f(Objects.requireNonNull(direction, "direction"));
        if (!origin.isFinite() || !normalized.isFinite() || normalized.lengthSquared() <= Frames.EPSILON
                || !Float.isFinite(maximumDistance) || maximumDistance <= 0f) {
            return java.util.Optional.empty();
        }
        return backend.raycast(origin, normalized.normalize(), maximumDistance);
    }

    public boolean beginDrag(SimBody body, Vector3f worldPoint) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(worldPoint, "worldPoint");
        if (!ownsDynamicBody(body) || !worldPoint.isFinite() || draggedBody != null) return false;
        if (!backend.beginDrag(body, worldPoint, dragSettings)) return false;
        draggedBody = body;
        return true;
    }

    public boolean beginDrag(RayHit hit) {
        return hit != null && beginDrag(hit.body(), hit.point());
    }

    public void updateDragTarget(Vector3f worldTarget, float frameSeconds) {
        Objects.requireNonNull(worldTarget, "worldTarget");
        if (draggedBody == null || !worldTarget.isFinite()) return;
        backend.updateDrag(worldTarget, frameSeconds);
    }

    public void endDrag() {
        backend.endDrag();
        draggedBody = null;
    }

    public boolean dragging() { return draggedBody != null; }
    public SimBody draggedBody() { return draggedBody; }
    public DragSettings dragSettings() { return dragSettings; }
    public void setDragSettings(DragSettings settings) {
        dragSettings = Objects.requireNonNull(settings, "dragSettings");
    }

    public PlaneCollider addPlaneCollider(Vector3f normal, float offset,
                                          float friction, float restitution) {
        Vector3f normalized = new Vector3f(Objects.requireNonNull(normal, "normal"));
        if (!normalized.isFinite() || normalized.lengthSquared() <= Frames.EPSILON) {
            throw new IllegalArgumentException("plane normal must be finite and non-zero");
        }
        PlaneCollider plane = new PlaneCollider(normalized.normalize(), offset,
                Math.max(0f, friction), Math.clamp(restitution, 0f, 1f));
        backend.addPlane(plane);
        return plane;
    }

    public PlaneCollider addGroundPlane(float y, float friction, float restitution) {
        return addPlaneCollider(new Vector3f(0f, 1f, 0f), y, friction, restitution);
    }

    public void removePlaneCollider(PlaneCollider plane) { backend.removePlane(plane); }
    public void clearPlaneColliders() { backend.clearPlanes(); }

    public StaticBoxCollider addStaticBoxCollider(Vector3f minimum, Vector3f maximum,
                                                  float friction, float restitution) {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (!minimum.isFinite() || !maximum.isFinite()
                || maximum.x <= minimum.x || maximum.y <= minimum.y || maximum.z <= minimum.z) {
            throw new IllegalArgumentException("static box bounds must be finite and ordered");
        }
        StaticBoxCollider box = new StaticBoxCollider(minimum, maximum,
                Math.max(0f, friction), Math.clamp(restitution, 0f, 1f));
        backend.addStaticBox(box);
        return box;
    }

    public void removeStaticBoxCollider(StaticBoxCollider box) { backend.removeStaticBox(box); }
    public void clearStaticBoxColliders() { backend.clearStaticBoxes(); }

    public StaticEnvironmentMesh addEnvironmentMesh(float friction, float restitution) {
        StaticEnvironmentMesh mesh = new StaticEnvironmentMesh(
                Math.max(0f, friction), Math.clamp(restitution, 0f, 1f));
        backend.addEnvironmentMesh(mesh);
        return mesh;
    }

    public void removeEnvironmentMesh(StaticEnvironmentMesh mesh) { backend.removeEnvironmentMesh(mesh); }
    public CollisionEnvironment collisionEnvironment() { return collisionEnvironment; }

    public void setCollisionEnvironment(CollisionEnvironment environment) {
        if (collisionEnvironment == environment) return;
        if (collisionEnvironment instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to detach the previous collision environment", exception);
            }
        }
        collisionEnvironment = environment;
    }

    public void excludePair(SimBody bodyA, SimBody bodyB) {
        backend.excludePair(Objects.requireNonNull(bodyA, "bodyA"),
                Objects.requireNonNull(bodyB, "bodyB"));
    }

    public Vector3f gravity() { return new Vector3f(gravity); }
    public void setGravity(Vector3f value) {
        gravity.set(Objects.requireNonNull(value, "gravity"));
        backend.setGravity(gravity);
    }

    /** Retained for source compatibility; Box3D uses substeps, not iteration counts. */
    public int solverIterations() { return solverIterations; }
    public void setSolverIterations(int value) {
        if (value < 1 || value > 128) throw new IllegalArgumentException("solverIterations must be within [1, 128]");
        solverIterations = value;
    }

    public boolean collisionsEnabled() { return collisionsEnabled; }
    public void setCollisionsEnabled(boolean enabled) {
        collisionsEnabled = enabled;
        backend.setCollisionsEnabled(enabled);
    }

    public boolean continuousCollisionEnabled() { return continuousCollisionEnabled; }
    public void setContinuousCollisionEnabled(boolean enabled) {
        continuousCollisionEnabled = enabled;
        backend.setContinuousEnabled(enabled);
    }

    public boolean selfCollisionsEnabled() { return selfCollisionsEnabled; }
    public void setSelfCollisionsEnabled(boolean enabled) {
        selfCollisionsEnabled = enabled;
        backend.setSelfCollisionsEnabled(enabled);
    }

    public int selfContactCount() { return backend.contactSummary().bodyContacts(); }
    public int staticContactBodyCount() { return backend.contactSummary().staticBodies(); }

    /** Contact points currently owned by Box3D for one registered body. */
    public List<BodyContact> contacts(SimBody body) {
        ensureOpen();
        if (body == null || !bodies.contains(body)) return List.of();
        return backend.contacts(body);
    }
    public float restitutionThreshold() { return restitutionThreshold; }
    public void setRestitutionThreshold(float threshold) {
        if (!Float.isFinite(threshold) || threshold < 0f) {
            throw new IllegalArgumentException("restitution threshold must be finite and non-negative");
        }
        restitutionThreshold = threshold;
        backend.setRestitutionThreshold(threshold);
    }

    public float simulationHertz() { return simulationHertz; }
    public void setSimulationHertz(float value) {
        if (!Float.isFinite(value) || value < 10f || value > 1000f) {
            throw new IllegalArgumentException("simulationHertz must be finite and within [10, 1000]");
        }
        simulationHertz = value;
        accumulatedTime = 0f;
    }

    public int substepCount() { return substepCount; }
    public void setSubstepCount(int value) {
        if (value < 1 || value > 50) throw new IllegalArgumentException("substepCount must be within [1, 50]");
        substepCount = value;
    }

    public float constraintHertz() { return constraintHertz; }
    public float constraintDampingRatio() { return constraintDampingRatio; }
    public void setConstraintTuning(float hertz, float dampingRatio) {
        if (!Float.isFinite(hertz) || hertz < 0f
                || !Float.isFinite(dampingRatio) || dampingRatio < 0f) {
            throw new IllegalArgumentException("constraint tuning must be finite and non-negative");
        }
        constraintHertz = hertz;
        constraintDampingRatio = dampingRatio;
        backend.setConstraintTuning(hertz, dampingRatio);
    }

    public void setSpeedLimits(float linear, float angular) {
        if (!Float.isFinite(linear) || linear <= 0f || !Float.isFinite(angular) || angular <= 0f) {
            throw new IllegalArgumentException("speed limits must be finite and positive");
        }
        maxLinearSpeed = linear;
        maxAngularSpeed = angular;
        backend.setMaximumLinearSpeed(linear);
    }

    public int maxFixedStepsPerUpdate() { return maxFixedStepsPerUpdate; }
    public void setMaxFixedStepsPerUpdate(int maximum) {
        if (maximum < 1 || maximum > 1000) {
            throw new IllegalArgumentException("maxFixedStepsPerUpdate must be within [1, 1000]");
        }
        maxFixedStepsPerUpdate = maximum;
    }

    public int lastFixedStepCount() { return lastFixedStepCount; }
    public float droppedSimulationTime() { return droppedSimulationTime; }
    public float interpolationAlpha() { return Math.clamp(accumulatedTime * simulationHertz, 0f, 1f); }
    public boolean sleeping() { return sleeping; }
    public float sleepTime() { return sleepTime; }
    public boolean sleepingEnabled() { return sleepingEnabled; }
    public void setSleepingEnabled(boolean enabled) {
        sleepingEnabled = enabled;
        backend.setSleepingEnabled(enabled);
        if (!enabled) wake();
    }

    /**
     * Maps the legacy linear/angular thresholds to Box3D's per-body point-velocity threshold.
     * Box3D owns its fixed sleep delay, so {@code delaySeconds} is validation-only.
     */
    public void setSleepingThresholds(float linearSpeed, float angularSpeed, float delaySeconds) {
        if (!Float.isFinite(linearSpeed) || linearSpeed < 0f
                || !Float.isFinite(angularSpeed) || angularSpeed < 0f
                || !Float.isFinite(delaySeconds) || delaySeconds < 0f) {
            throw new IllegalArgumentException("sleep thresholds must be finite and non-negative");
        }
        backend.setSleepingThresholds(linearSpeed, angularSpeed);
    }

    public void wake() {
        sleeping = false;
        sleepTime = 0f;
        backend.wake();
    }

    public void resetState() {
        accumulatedTime = 0f;
        lastFixedStepCount = 0;
        droppedSimulationTime = 0f;
        sleeping = false;
        sleepTime = 0f;
        endDrag();
    }

    public void step(float deltaSeconds, KinematicDriver driver) {
        ensureOpen();
        Objects.requireNonNull(driver, "driver");
        lastFixedStepCount = 0;
        if (bodies.isEmpty() || !(deltaSeconds > 0f) || !Float.isFinite(deltaSeconds)) return;
        float fixedTimeStep = 1f / simulationHertz;
        accumulatedTime += deltaSeconds;
        float maximumAccepted = maxFixedStepsPerUpdate * fixedTimeStep;
        if (accumulatedTime > maximumAccepted) {
            float remainder = accumulatedTime % fixedTimeStep;
            if (fixedTimeStep - remainder <= Frames.EPSILON) remainder = 0f;
            droppedSimulationTime += Math.max(0f, accumulatedTime - maximumAccepted - remainder);
            accumulatedTime = maximumAccepted + remainder;
        }
        int count = Math.min((int)Math.floor((accumulatedTime + Frames.EPSILON) / fixedTimeStep),
                maxFixedStepsPerUpdate);
        accumulatedTime = Math.max(0f, accumulatedTime - count * fixedTimeStep);
        lastFixedStepCount = count;
        if (count == 0) return;

        if (collisionEnvironment != null) collisionEnvironment.update(this);
        for (int index = 0; index < count; index++) {
            // Controllers may enqueue Box3D forces, which are cleared after
            // each native step, so evaluate them once per fixed step too.
            driver.beginStep(this);
            for (SimBody body : bodies) {
                body.interpolationPositionRef().set(body.positionRef());
                body.interpolationRotationRef().set(body.rotationRef()).normalize();
            }
            backend.step(fixedTimeStep, substepCount, driver);
        }
        sleeping = sleepingEnabled && draggedBody == null && backend.sleeping();
        sleepTime = sleeping ? sleepTime + count * fixedTimeStep : 0f;
    }

    @Override
    public void close() {
        if (closed) return;
        setCollisionEnvironment(null);
        backend.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("physics world is closed");
    }
}
