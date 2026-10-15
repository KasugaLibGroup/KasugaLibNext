package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Native Box3D ownership and state synchronization for {@link RigidBodyWorld}. */
final class Box3DBackend implements AutoCloseable {
    private static final AtomicInteger NEXT_COLLISION_GROUP = new AtomicInteger(-1);
    private static final float STATE_EPSILON = 1e-10f;
    // Box3D's maintained ragdoll sample uses 2 Hz/0.7 on mesh terrain,
    // 5 N*m joint friction and 0.2 rolling resistance on the body capsules.
    private static final float RAGDOLL_ALIGNMENT_HERTZ = 2f;
    private static final float RAGDOLL_ALIGNMENT_DAMPING_RATIO = 0.7f;
    private static final float RAGDOLL_JOINT_FRICTION_TORQUE = 5f;
    private static final float PLANE_HALF_THICKNESS = 500f;
    private static final float PLANE_HALF_EXTENT = 4096f;

    record ContactSummary(int bodyContacts, int staticBodies) {}

    private static final class BodyHandle {
        final long nativeId;
        final Vector3f position = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
        final Vector3f linearVelocity = new Vector3f();
        final Vector3f angularVelocity = new Vector3f();
        boolean enabled = true;

        BodyHandle(long nativeId, SimBody body) {
            this.nativeId = nativeId;
            capture(body);
        }

        void capture(SimBody body) {
            position.set(body.positionRef());
            rotation.set(body.rotationRef());
            linearVelocity.set(body.linearVelocityRef());
            angularVelocity.set(body.angularVelocityRef());
        }
    }

    private record EnvironmentMeshHandle(long revision, long bodyId, long meshPointer) {}

    private final int worldId;
    private final int disabledSelfCollisionGroup = NEXT_COLLISION_GROUP.getAndDecrement();
    private final Map<SimBody, BodyHandle> bodies = new IdentityHashMap<>();
    private final Map<Long, SimBody> bodiesByNativeId = new java.util.HashMap<>();
    private final Map<BallJoint, Long> joints = new IdentityHashMap<>();
    private final Map<PhysicsJoint, Long> genericJoints = new IdentityHashMap<>();
    private final Map<PlaneCollider, Long> planes = new IdentityHashMap<>();
    private final Map<StaticBoxCollider, Long> staticBoxes = new IdentityHashMap<>();
    private final List<StaticEnvironmentMesh> environmentMeshes = new ArrayList<>();
    private final Map<StaticEnvironmentMesh, EnvironmentMeshHandle> environmentMeshHandles
            = new IdentityHashMap<>();
    private final float[] state = new float[13];
    private final float[] rayHit = new float[7];
    private long dragAnchor;
    private long dragJoint;
    private boolean collisionsEnabled = true;
    private boolean selfCollisionsEnabled = true;
    private boolean continuousEnabled;
    private float constraintHertz = 60f;
    private float constraintDampingRatio = 2f;
    private float sleepLinearThreshold = 0.05f;
    private float sleepAngularThreshold;
    private boolean closed;

    Box3DBackend(Vector3f gravity, boolean sleeping, boolean continuous) {
        NativeBox3D.requireAvailable();
        continuousEnabled = continuous;
        worldId = NativeBox3D.createWorld(gravity.x, gravity.y, gravity.z, sleeping, continuous);
        if (worldId == 0) throw new IllegalStateException("Box3D failed to create a world");
    }

    void addBody(SimBody body) {
        if (bodies.containsKey(body)) return;
        long categoryBits = 1L << body.collisionGroup();
        long maskBits = ~Integer.toUnsignedLong(body.nonCollisionMask());
        int nativeType = body.kinematic() ? NativeBox3D.KINEMATIC_BODY
                : body.inverseLinearMass() > 0f ? NativeBox3D.DYNAMIC_BODY : NativeBox3D.STATIC_BODY;
        Vector3f p = body.positionRef();
        Quaternionf q = body.rotationRef();
        Vector3f v = body.linearVelocityRef();
        Vector3f w = body.angularVelocityRef();
        float mass = body.inverseLinearMass() > 0f ? 1f / body.inverseLinearMass() : 0f;
        long id = NativeBox3D.createBody(worldId, nativeType,
                p.x, p.y, p.z, q.x, q.y, q.z, q.w,
                v.x, v.y, v.z, w.x, w.y, w.z,
                body.linearDamping(), body.angularDamping(),
                continuousEnabled);
        if (id == 0L) throw new IllegalStateException("Box3D failed to create a body");
        List<BodyShape> shapes = body.collisionShapes();
        if (shapes.isEmpty()) {
            NativeBox3D.destroyBody(id);
            throw new IllegalArgumentException("a Box3D body must contain at least one shape");
        }
        float density = nativeType == NativeBox3D.DYNAMIC_BODY ? 1f : 0f;
        long activeMask = collisionsEnabled ? maskBits : 0L;
        int groupIndex = selfCollisionGroup(body);
        for (BodyShape shape : shapes) {
            addShape(id, shape, density, body.friction(), body.restitution(), body.rollingResistance(),
                    categoryBits, activeMask, groupIndex);
        }
        NativeBox3D.finalizeBodyMass(id, mass);
        NativeBox3D.setBodySleepThreshold(id, sleepThreshold(body));
        bodies.put(body, new BodyHandle(id, body));
        bodiesByNativeId.put(id, body);
    }

    private static void addShape(long bodyId, BodyShape shape, float density,
                                 float friction, float restitution, float rollingResistance,
                                 long categoryBits, long maskBits, int groupIndex) {
        long shapeId;
        if (shape instanceof BodyShape.Sphere sphere) {
            Vector3f center = sphere.center();
            shapeId = NativeBox3D.addSphereShape(bodyId, center.x, center.y, center.z,
                    sphere.radius(), density, friction, restitution, rollingResistance,
                    categoryBits, maskBits, groupIndex);
        } else if (shape instanceof BodyShape.Box box) {
            Vector3f center = box.center();
            Quaternionf rotation = box.rotation();
            Vector3f half = box.halfExtents();
            shapeId = NativeBox3D.addBoxShape(bodyId,
                    center.x, center.y, center.z,
                    rotation.x, rotation.y, rotation.z, rotation.w,
                    half.x, half.y, half.z, density, friction, restitution, rollingResistance,
                    categoryBits, maskBits, groupIndex);
        } else if (shape instanceof BodyShape.Capsule capsule) {
            Vector3f a = capsule.centerA();
            Vector3f b = capsule.centerB();
            shapeId = NativeBox3D.addCapsuleShape(bodyId,
                    a.x, a.y, a.z, b.x, b.y, b.z, capsule.radius(),
                    density, friction, restitution, rollingResistance,
                    categoryBits, maskBits, groupIndex);
        } else {
            throw new IllegalArgumentException("unsupported Box3D shape: " + shape.getClass().getName());
        }
        if (shapeId == 0L) throw new IllegalStateException("Box3D failed to create a body shape");
    }

    void removeBody(SimBody body) {
        BodyHandle removed = bodies.remove(body);
        if (removed != null) {
            bodiesByNativeId.remove(removed.nativeId);
            NativeBox3D.destroyBody(removed.nativeId);
        }
    }

    void addJoint(BallJoint joint) {
        if (joints.containsKey(joint)) return;
        BodyHandle a = bodies.get(joint.bodyA());
        BodyHandle b = bodies.get(joint.bodyB());
        if (a == null || b == null) throw new IllegalArgumentException("joint bodies must belong to the world");
        Frames.Pose localA = joint.localA;
        Frames.Pose localB = joint.localB;
        Vector3f twistAxis = joint.twistAxis();
        Quaternionf axisAdjustment = new Quaternionf().rotationTo(
                0f, 0f, 1f, twistAxis.x, twistAxis.y, twistAxis.z);
        Quaternionf rotationA = new Quaternionf(localA.rotation).mul(axisAdjustment).normalize();
        Quaternionf rotationB = new Quaternionf(localB.rotation).mul(axisAdjustment).normalize();
        float coneAngle;
        float lowerTwist;
        float upperTwist;
        BallJoint.RotationLimiter limiter = joint.rotationLimiter();
        if (limiter != null && Float.isFinite(limiter.box3dConeAngle())) {
            coneAngle = limiter.box3dConeAngle();
            lowerTwist = limiter.box3dLowerTwistAngle();
            upperTwist = limiter.box3dUpperTwistAngle();
        } else {
            Vector3f minimum = joint.rotationMinimum();
            Vector3f maximum = joint.rotationMaximum();
            int axis = dominantAxis(twistAxis);
            lowerTwist = component(minimum, axis);
            upperTwist = component(maximum, axis);
            coneAngle = perpendicularExtent(minimum, maximum, axis);
        }
        long id = NativeBox3D.createSphericalJoint(worldId, a.nativeId, b.nativeId,
                localA.position.x, localA.position.y, localA.position.z,
                rotationA.x, rotationA.y, rotationA.z, rotationA.w,
                localB.position.x, localB.position.y, localB.position.z,
                rotationB.x, rotationB.y, rotationB.z, rotationB.w,
                constraintHertz, constraintDampingRatio,
                coneAngle, lowerTwist, upperTwist, false);
        if (id == 0L) throw new IllegalStateException("Box3D failed to create a spherical joint");
        if (limiter != null && limiter.stiffness() > 0f
                && joint.bodyA().profiledRagdollBody()
                && joint.bodyB().profiledRagdollBody()) {
            NativeBox3D.configureSphericalJointDynamics(id,
                    joint.bodyB().ragdollAlignmentSpring()
                            ? RAGDOLL_ALIGNMENT_HERTZ : 0f,
                    RAGDOLL_ALIGNMENT_DAMPING_RATIO,
                    RAGDOLL_JOINT_FRICTION_TORQUE * limiter.stiffness());
        }
        joints.put(joint, id);
    }

    void removeJoint(BallJoint joint) {
        Long removed = joints.remove(joint);
        if (removed != null && removed != 0L) NativeBox3D.destroyJoint(removed);
    }

    void addJoint(PhysicsJoint joint) {
        if (genericJoints.containsKey(joint)) return;
        BodyHandle a = bodies.get(joint.bodyA());
        BodyHandle b = bodies.get(joint.bodyB());
        if (a == null || b == null) throw new IllegalArgumentException("joint bodies must belong to the world");
        Frames.Pose localA = joint.localFrameA();
        Frames.Pose localB = joint.localFrameB();
        long id = switch (joint) {
            case WeldJoint weld -> NativeBox3D.createWeldJoint(worldId, a.nativeId, b.nativeId,
                    poseX(localA), posY(localA), posZ(localA),
                    quatX(localA), quatY(localA), quatZ(localA), quatW(localA),
                    poseX(localB), posY(localB), posZ(localB),
                    quatX(localB), quatY(localB), quatZ(localB), quatW(localB),
                    weld.linearHertz(), weld.angularHertz(),
                    weld.linearDampingRatio(), weld.angularDampingRatio(),
                    joint.constraintHertz(), joint.constraintDampingRatio(),
                    joint.collideConnected());
            case DistanceJoint distance -> NativeBox3D.createDistanceJoint(worldId, a.nativeId, b.nativeId,
                    poseX(localA), posY(localA), posZ(localA),
                    quatX(localA), quatY(localA), quatZ(localA), quatW(localA),
                    poseX(localB), posY(localB), posZ(localB),
                    quatX(localB), quatY(localB), quatZ(localB), quatW(localB),
                    distance.length(),
                    distance.springEnabled(), distance.hertz(), distance.dampingRatio(),
                    distance.limitEnabled(), distance.minLength(), distance.maxLength(),
                    distance.motorEnabled(), distance.motorSpeed(), distance.maxMotorForce(),
                    joint.constraintHertz(), joint.constraintDampingRatio(),
                    joint.collideConnected());
            case RevoluteJoint revolute -> NativeBox3D.createRevoluteJoint(worldId, a.nativeId, b.nativeId,
                    poseX(localA), posY(localA), posZ(localA),
                    quatX(localA), quatY(localA), quatZ(localA), quatW(localA),
                    poseX(localB), posY(localB), posZ(localB),
                    quatX(localB), quatY(localB), quatZ(localB), quatW(localB),
                    revolute.targetAngle(),
                    revolute.springEnabled(), revolute.springHertz(), revolute.springDampingRatio(),
                    revolute.limitEnabled(), revolute.lowerAngle(), revolute.upperAngle(),
                    revolute.motorEnabled(), revolute.maxMotorTorque(), revolute.motorSpeed(),
                    joint.constraintHertz(), joint.constraintDampingRatio(),
                    joint.collideConnected());
            case PrismaticJoint prismatic -> NativeBox3D.createPrismaticJoint(worldId, a.nativeId, b.nativeId,
                    poseX(localA), posY(localA), posZ(localA),
                    quatX(localA), quatY(localA), quatZ(localA), quatW(localA),
                    poseX(localB), posY(localB), posZ(localB),
                    quatX(localB), quatY(localB), quatZ(localB), quatW(localB),
                    prismatic.springEnabled(), prismatic.springHertz(), prismatic.springDampingRatio(),
                    prismatic.targetTranslation(),
                    prismatic.limitEnabled(), prismatic.lowerTranslation(), prismatic.upperTranslation(),
                    prismatic.motorEnabled(), prismatic.maxMotorForce(), prismatic.motorSpeed(),
                    joint.constraintHertz(), joint.constraintDampingRatio(),
                    joint.collideConnected());
        };
        if (id == 0L) throw new IllegalStateException("Box3D failed to create " + joint.getClass().getSimpleName());
        genericJoints.put(joint, id);
        joint.nativeId = id;
    }

    private static float poseX(Frames.Pose pose) { return pose.position.x; }
    private static float posY(Frames.Pose pose) { return pose.position.y; }
    private static float posZ(Frames.Pose pose) { return pose.position.z; }
    private static float quatX(Frames.Pose pose) { return pose.rotation.x; }
    private static float quatY(Frames.Pose pose) { return pose.rotation.y; }
    private static float quatZ(Frames.Pose pose) { return pose.rotation.z; }
    private static float quatW(Frames.Pose pose) { return pose.rotation.w; }

    void removeJoint(PhysicsJoint joint) {
        Long removed = genericJoints.remove(joint);
        if (removed != null && removed != 0L) NativeBox3D.destroyJoint(removed);
        joint.nativeId = 0L;
    }

    void step(float timeStep, int subStepCount, RigidBodyWorld.KinematicDriver driver) {
        syncEnvironmentMeshes();
        for (Map.Entry<SimBody, BodyHandle> entry : bodies.entrySet()) {
            SimBody body = entry.getKey();
            BodyHandle handle = entry.getValue();
            if (!handle.enabled) continue;
            body.previousPositionRef().set(body.positionRef());
            body.previousRotationRef().set(body.rotationRef());
            if (body.kinematic()) {
                Frames.Pose target = driver.kinematicTarget(body);
                NativeBox3D.setBodyTarget(handle.nativeId,
                        target.position.x, target.position.y, target.position.z,
                        target.rotation.x, target.rotation.y, target.rotation.z, target.rotation.w,
                        timeStep);
            } else {
                pushExternalEdits(body, handle);
            }
        }

        NativeBox3D.step(worldId, timeStep, subStepCount);
        readStates();
    }

    private void pushExternalEdits(SimBody body, BodyHandle handle) {
        Vector3f p = body.positionRef();
        Quaternionf q = body.rotationRef();
        if (!p.equals(handle.position, STATE_EPSILON) || !q.equals(handle.rotation, STATE_EPSILON)) {
            NativeBox3D.setBodyTransform(handle.nativeId, p.x, p.y, p.z, q.x, q.y, q.z, q.w);
        }
        Vector3f v = body.linearVelocityRef();
        Vector3f w = body.angularVelocityRef();
        if (!v.equals(handle.linearVelocity, STATE_EPSILON) || !w.equals(handle.angularVelocity, STATE_EPSILON)) {
            NativeBox3D.setBodyVelocity(handle.nativeId, v.x, v.y, v.z, w.x, w.y, w.z);
        }
    }

    private void readStates() {
        for (Map.Entry<SimBody, BodyHandle> entry : bodies.entrySet()) {
            SimBody body = entry.getKey();
            BodyHandle handle = entry.getValue();
            if (!handle.enabled) continue;
            NativeBox3D.readBodyState(handle.nativeId, state);
            body.positionRef().set(state[0], state[1], state[2]);
            body.rotationRef().set(state[3], state[4], state[5], state[6]).normalize();
            body.linearVelocityRef().set(state[7], state[8], state[9]);
            body.angularVelocityRef().set(state[10], state[11], state[12]);
            handle.capture(body);
        }
    }

    boolean applyImpulse(SimBody body, Vector3f impulse, Vector3f point) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return false;
        if (point == null) {
            NativeBox3D.applyCenterImpulse(handle.nativeId, impulse.x, impulse.y, impulse.z);
        } else {
            NativeBox3D.applyImpulse(handle.nativeId, impulse.x, impulse.y, impulse.z,
                    point.x, point.y, point.z);
        }
        readState(body, handle);
        return true;
    }

    boolean applyAngularImpulse(SimBody body, Vector3f impulse) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return false;
        NativeBox3D.applyAngularImpulse(handle.nativeId, impulse.x, impulse.y, impulse.z);
        readState(body, handle);
        return true;
    }

    boolean applyForce(SimBody body, Vector3f force, Vector3f point) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return false;
        if (point == null) {
            NativeBox3D.applyForce(handle.nativeId, force.x, force.y, force.z);
        } else {
            NativeBox3D.applyForceAtPoint(handle.nativeId, force.x, force.y, force.z,
                    point.x, point.y, point.z);
        }
        return true;
    }

    boolean applyTorque(SimBody body, Vector3f torque) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return false;
        NativeBox3D.applyTorque(handle.nativeId, torque.x, torque.y, torque.z);
        return true;
    }

    boolean setGravityScale(SimBody body, float scale) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return false;
        NativeBox3D.setBodyGravityScale(handle.nativeId, scale);
        return true;
    }

    float gravityScale(SimBody body) {
        BodyHandle handle = bodies.get(body);
        return handle == null ? Float.NaN : NativeBox3D.bodyGravityScale(handle.nativeId);
    }

    boolean setAwake(SimBody body, boolean awake) {
        BodyHandle handle = bodies.get(body);
        if (handle == null || !handle.enabled) return false;
        NativeBox3D.setBodyAwake(handle.nativeId, awake);
        return true;
    }

    boolean awake(SimBody body) {
        BodyHandle handle = bodies.get(body);
        return handle != null && handle.enabled && NativeBox3D.bodyAwake(handle.nativeId);
    }

    boolean setBodyEnabled(SimBody body, boolean enabled) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return false;
        if (handle.enabled == enabled) return true;
        NativeBox3D.setBodyEnabled(handle.nativeId, enabled);
        handle.enabled = enabled;
        return true;
    }

    boolean bodyEnabled(SimBody body) {
        BodyHandle handle = bodies.get(body);
        return handle != null && handle.enabled;
    }

    java.util.Optional<RayHit> raycast(Vector3f origin, Vector3f direction, float maximumDistance) {
        if (!collisionsEnabled) setQueryFilters(true);
        long bodyId = NativeBox3D.raycast(worldId, origin.x, origin.y, origin.z,
                direction.x, direction.y, direction.z, maximumDistance, rayHit);
        if (!collisionsEnabled) setQueryFilters(false);
        SimBody body = bodiesByNativeId.get(bodyId);
        if (body == null) return java.util.Optional.empty();
        return java.util.Optional.of(new RayHit(body,
                new Vector3f(rayHit[0], rayHit[1], rayHit[2]), rayHit[6]));
    }

    boolean beginDrag(SimBody body, Vector3f worldPoint, DragSettings settings) {
        BodyHandle handle = bodies.get(body);
        if (handle == null || dragJoint != 0L) return false;
        long[] ids = NativeBox3D.createDrag(worldId, handle.nativeId,
                worldPoint.x, worldPoint.y, worldPoint.z,
                Math.max(0f, settings.biasRate()), 2f);
        if (ids == null || ids.length != 2 || ids[0] == 0L || ids[1] == 0L) return false;
        dragAnchor = ids[0];
        dragJoint = ids[1];
        return true;
    }

    void updateDrag(Vector3f target, float timeStep) {
        if (dragAnchor != 0L) {
            NativeBox3D.updateDrag(dragAnchor, target.x, target.y, target.z,
                    Math.max(timeStep, 1f / 1000f));
        }
    }

    void endDrag() {
        if (dragAnchor == 0L) return;
        NativeBox3D.destroyDrag(dragAnchor, dragJoint);
        dragAnchor = 0L;
        dragJoint = 0L;
    }

    ContactSummary contactSummary() {
        int bodyContacts = 0;
        int staticBodies = 0;
        for (BodyHandle handle : bodies.values()) {
            long summary = NativeBox3D.bodyContactSummary(handle.nativeId);
            bodyContacts += (int)summary;
            if ((summary & (1L << 32)) != 0L) staticBodies++;
        }
        return new ContactSummary(bodyContacts, staticBodies);
    }

    List<BodyContact> contacts(SimBody body) {
        BodyHandle handle = bodies.get(body);
        if (handle == null) return List.of();
        int capacity = NativeBox3D.bodyContactCapacity(handle.nativeId);
        if (capacity <= 0) return List.of();
        long[] otherIds = new long[capacity];
        float[] values = new float[capacity * 10];
        int count = NativeBox3D.readBodyContacts(handle.nativeId, otherIds, values);
        List<BodyContact> result = new ArrayList<>(count);
        for (int index = 0; index < count; ++index) {
            int offset = index * 10;
            result.add(new BodyContact(body,
                    java.util.Optional.ofNullable(bodiesByNativeId.get(otherIds[index])),
                    new Vector3f(values[offset], values[offset + 1], values[offset + 2]),
                    new Vector3f(values[offset + 3], values[offset + 4], values[offset + 5]),
                    values[offset + 6], values[offset + 7], values[offset + 8], values[offset + 9]));
        }
        return List.copyOf(result);
    }

    void excludePair(SimBody bodyA, SimBody bodyB) {
        BodyHandle a = bodies.get(bodyA);
        BodyHandle b = bodies.get(bodyB);
        if (a != null && b != null) NativeBox3D.createFilterJoint(worldId, a.nativeId, b.nativeId);
    }

    void wake() {
        for (BodyHandle handle : bodies.values()) {
            if (handle.enabled) NativeBox3D.wakeBody(handle.nativeId);
        }
    }

    boolean sleeping() {
        boolean hasDynamic = false;
        for (Map.Entry<SimBody, BodyHandle> entry : bodies.entrySet()) {
            if (!entry.getValue().enabled || entry.getKey().inverseLinearMass() <= 0f) continue;
            hasDynamic = true;
            if (NativeBox3D.bodyAwake(entry.getValue().nativeId)) return false;
        }
        return hasDynamic;
    }

    void setGravity(Vector3f gravity) {
        NativeBox3D.setGravity(worldId, gravity.x, gravity.y, gravity.z);
    }

    void setSleepingEnabled(boolean enabled) {
        NativeBox3D.setSleepingEnabled(worldId, enabled);
    }

    void setSleepingThresholds(float linearSpeed, float angularSpeed) {
        sleepLinearThreshold = linearSpeed;
        sleepAngularThreshold = angularSpeed;
        for (Map.Entry<SimBody, BodyHandle> entry : bodies.entrySet()) {
            NativeBox3D.setBodySleepThreshold(entry.getValue().nativeId,
                    sleepThreshold(entry.getKey()));
        }
    }

    private float sleepThreshold(SimBody body) {
        float extent = 0f;
        for (BodyShape shape : body.collisionShapes()) {
            extent = Math.max(extent, shape.boundingRadius());
        }
        return sleepLinearThreshold + sleepAngularThreshold * extent;
    }

    void setContinuousEnabled(boolean enabled) {
        continuousEnabled = enabled;
        NativeBox3D.setContinuousEnabled(worldId, enabled);
        for (BodyHandle handle : bodies.values()) NativeBox3D.setBodyBullet(handle.nativeId, enabled);
    }

    void setRestitutionThreshold(float threshold) {
        NativeBox3D.setRestitutionThreshold(worldId, threshold);
    }

    void setMaximumLinearSpeed(float speed) {
        NativeBox3D.setMaximumLinearSpeed(worldId, speed);
    }

    void setConstraintTuning(float hertz, float dampingRatio) {
        constraintHertz = hertz;
        constraintDampingRatio = dampingRatio;
        // Joint definitions are immutable. Recreate them with the new tuning.
        List<BallJoint> existing = new ArrayList<>(joints.keySet());
        for (BallJoint joint : existing) removeJoint(joint);
        for (BallJoint joint : existing) addJoint(joint);
    }

    void setCollisionsEnabled(boolean enabled) {
        collisionsEnabled = enabled;
        refreshFilters();
    }

    void setSelfCollisionsEnabled(boolean enabled) {
        selfCollisionsEnabled = enabled;
        refreshFilters();
    }

    private void refreshFilters() {
        setQueryFilters(collisionsEnabled);
    }

    private void setQueryFilters(boolean enabled) {
        for (Map.Entry<SimBody, BodyHandle> entry : bodies.entrySet()) {
            SimBody body = entry.getKey();
            long category = 1L << body.collisionGroup();
            long mask = enabled ? ~Integer.toUnsignedLong(body.nonCollisionMask()) : 0L;
            NativeBox3D.setBodyFilter(entry.getValue().nativeId, category, mask,
                    selfCollisionGroup(body));
        }
    }

    private int selfCollisionGroup(SimBody body) {
        // The negative group is a humanoid-profile policy, not a whole-world
        // policy. Authored PMX skirt/hair bodies must keep their category/mask
        // filters so neighboring cloth chains can still interact as authored.
        if (body.authoredSecondaryBody()) return 0;
        if (body.selfCollisionGroup() < 0) return body.selfCollisionGroup();
        return selfCollisionsEnabled ? 0 : disabledSelfCollisionGroup;
    }

    private void readState(SimBody body, BodyHandle handle) {
        NativeBox3D.readBodyState(handle.nativeId, state);
        body.positionRef().set(state[0], state[1], state[2]);
        body.rotationRef().set(state[3], state[4], state[5], state[6]).normalize();
        body.linearVelocityRef().set(state[7], state[8], state[9]);
        body.angularVelocityRef().set(state[10], state[11], state[12]);
        handle.capture(body);
    }

    void addPlane(PlaneCollider plane) {
        Vector3f normal = plane.normal().normalize();
        Quaternionf rotation = new Quaternionf().rotationTo(0f, 1f, 0f, normal.x, normal.y, normal.z);
        Vector3f center = new Vector3f(normal).mul(plane.offset() - PLANE_HALF_THICKNESS);
        long id = createStaticBox(new Vector3f(PLANE_HALF_EXTENT, PLANE_HALF_THICKNESS, PLANE_HALF_EXTENT),
                center, rotation, plane.friction(), plane.restitution());
        planes.put(plane, id);
    }

    void removePlane(PlaneCollider plane) {
        destroyStatic(planes.remove(plane));
    }

    void clearPlanes() {
        for (long id : planes.values()) NativeBox3D.destroyBody(id);
        planes.clear();
    }

    void addStaticBox(StaticBoxCollider box) {
        staticBoxes.put(box, createStaticBox(box));
    }

    void removeStaticBox(StaticBoxCollider box) {
        destroyStatic(staticBoxes.remove(box));
    }

    void clearStaticBoxes() {
        for (long id : staticBoxes.values()) NativeBox3D.destroyBody(id);
        staticBoxes.clear();
    }

    void addEnvironmentMesh(StaticEnvironmentMesh mesh) {
        environmentMeshes.add(mesh);
    }

    void removeEnvironmentMesh(StaticEnvironmentMesh mesh) {
        environmentMeshes.remove(mesh);
        syncEnvironmentMeshes();
    }

    private void syncEnvironmentMeshes() {
        environmentMeshHandles.entrySet().removeIf(entry -> {
            if (environmentMeshes.contains(entry.getKey())) return false;
            destroyEnvironmentMesh(entry.getValue());
            return true;
        });
        for (StaticEnvironmentMesh mesh : environmentMeshes) {
            EnvironmentMeshHandle current = environmentMeshHandles.get(mesh);
            if (current != null && current.revision == mesh.revision()) continue;
            if (current != null) destroyEnvironmentMesh(current);
            StaticEnvironmentMesh.TerrainGeometry geometry = mesh.geometry();
            if (geometry.indices().length == 0) {
                environmentMeshHandles.put(mesh,
                        new EnvironmentMeshHandle(mesh.revision(), 0L, 0L));
                continue;
            }
            long[] nativeMesh = NativeBox3D.createStaticMesh(worldId,
                    geometry.vertices(), geometry.indices(), mesh.friction(), mesh.restitution());
            if (nativeMesh == null || nativeMesh.length != 2
                    || nativeMesh[0] == 0L || nativeMesh[1] == 0L) {
                throw new IllegalStateException("Box3D failed to create the terrain mesh");
            }
            environmentMeshHandles.put(mesh,
                    new EnvironmentMeshHandle(mesh.revision(), nativeMesh[0], nativeMesh[1]));
        }
    }

    private static void destroyEnvironmentMesh(EnvironmentMeshHandle handle) {
        if (handle.bodyId != 0L || handle.meshPointer != 0L) {
            NativeBox3D.destroyStaticMesh(handle.bodyId, handle.meshPointer);
        }
    }

    private long createStaticBox(StaticBoxCollider box) {
        Vector3f minimum = box.minimumRef();
        Vector3f maximum = box.maximumRef();
        Vector3f halfExtents = new Vector3f(maximum).sub(minimum).mul(0.5f);
        Vector3f center = new Vector3f(minimum).add(maximum).mul(0.5f);
        return createStaticBox(halfExtents, center, new Quaternionf(), box.friction(), box.restitution());
    }

    private long createStaticBox(Vector3f halfExtents, Vector3f center, Quaternionf rotation,
                                 float friction, float restitution) {
        long id = NativeBox3D.createBody(worldId, NativeBox3D.STATIC_BODY,
                center.x, center.y, center.z, rotation.x, rotation.y, rotation.z, rotation.w,
                0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, false);
        addShape(id, new BodyShape.Box(halfExtents), 0f, friction, restitution, 0f,
                Long.MIN_VALUE, -1L, 0);
        return id;
    }

    private static void destroyStatic(Long id) {
        if (id != null) NativeBox3D.destroyBody(id);
    }

    private static int dominantAxis(Vector3f axis) {
        float x = Math.abs(axis.x);
        float y = Math.abs(axis.y);
        float z = Math.abs(axis.z);
        return x >= y && x >= z ? 0 : y >= z ? 1 : 2;
    }

    private static float component(Vector3f vector, int axis) {
        return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
    }

    private static float perpendicularExtent(Vector3f minimum, Vector3f maximum, int twistAxis) {
        float result = 0f;
        for (int axis = 0; axis < 3; axis++) {
            if (axis == twistAxis) continue;
            result = Math.max(result, Math.max(Math.abs(component(minimum, axis)),
                    Math.abs(component(maximum, axis))));
        }
        return Math.clamp(result, 0f, (float)Math.PI);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        endDrag();
        for (EnvironmentMeshHandle handle : environmentMeshHandles.values()) {
            destroyEnvironmentMesh(handle);
        }
        environmentMeshHandles.clear();
        NativeBox3D.destroyWorld(worldId);
        bodies.clear();
        bodiesByNativeId.clear();
        joints.clear();
        genericJoints.clear();
        planes.clear();
        staticBoxes.clear();
        environmentMeshes.clear();
    }
}
