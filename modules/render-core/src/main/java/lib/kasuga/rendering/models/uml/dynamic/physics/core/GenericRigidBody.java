package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.List;
import java.util.function.Consumer;

/**
 * Standalone Java-side rigid-body description for {@link RigidBodyWorld}.
 *
 * <p>This is the reference {@link SimBody} implementation for arbitrary
 * physics content — props, debris, spawned blocks, vehicles, anything that is
 * not a model-bound ragdoll. Create one with a shape factory, place it in the
 * world, add it to a world's body list and step.</p>
 *
 * <pre>{@code
 * GenericRigidBody crate = GenericRigidBody.box(new Vector3f(0.5f), 8f)
 *         .at(2f, 5f, 1f)
 *         .friction(0.7f);
 * world.add(crate);
 * }</pre>
 */
public final class GenericRigidBody implements SimBody {
    private final Frames.Pose pose = new Pose();
    private final Frames.Pose previousPose = new Pose();
    private final Frames.Pose interpolationPose = new Pose();
    private final Vector3f linearVelocity = new Vector3f();
    private final Vector3f angularVelocity = new Vector3f();

    private final int shape;
    private final Vector3f shapeSize;
    private final List<BodyShape> collisionShapes;
    private final float inverseLinearMass;
    private final boolean kinematic;

    private float friction;
    private float restitution;
    private int collisionGroup;
    private int nonCollisionMask;
    private float linearDamping;
    private float angularDamping;
    private Runnable wake = () -> {};

    private GenericRigidBody(int shape, Vector3f shapeSize, float mass, boolean kinematic) {
        this(shape, shapeSize, null, mass, kinematic);
    }

    private GenericRigidBody(int shape, Vector3f shapeSize, List<BodyShape> collisionShapes,
                             float mass, boolean kinematic) {
        this.shape = shape;
        this.shapeSize = new Vector3f(shapeSize).absolute();
        this.collisionShapes = collisionShapes == null
                ? SimBody.super.collisionShapes() : List.copyOf(collisionShapes);
        if (this.collisionShapes.isEmpty()) {
            throw new IllegalArgumentException("a rigid body must contain at least one collision shape");
        }
        this.kinematic = kinematic;
        this.inverseLinearMass = kinematic || !(mass > 1e-7f) ? 0f : 1f / mass;
    }

    /** Dynamic sphere with radius and mass. */
    public static GenericRigidBody sphere(float radius, float mass) {
        if (!(radius > 0f)) throw new IllegalArgumentException("radius must be positive");
        return new GenericRigidBody(SHAPE_SPHERE, new Vector3f(radius), mass, false);
    }

    /** Dynamic oriented box with half extents and mass. */
    public static GenericRigidBody box(Vector3f halfExtents, float mass) {
        if (!halfExtents.isFinite() || halfExtents.x <= 0f || halfExtents.y <= 0f || halfExtents.z <= 0f) {
            throw new IllegalArgumentException("half extents must be finite and positive");
        }
        return new GenericRigidBody(SHAPE_BOX, new Vector3f(halfExtents), mass, false);
    }

    /** Dynamic Y-aligned capsule with radius and total cylinder height. */
    public static GenericRigidBody capsule(float radius, float height, float mass) {
        if (!(radius > 0f) || !(height >= 0f)) {
            throw new IllegalArgumentException("capsule dimensions must be non-negative");
        }
        return new GenericRigidBody(SHAPE_CAPSULE, new Vector3f(radius, height, 0f), mass, false);
    }

    /** Dynamic compound body made from local-space Box3D primitives. */
    public static GenericRigidBody compound(List<? extends BodyShape> shapes, float mass) {
        List<BodyShape> values = List.copyOf(Objects.requireNonNull(shapes, "shapes"));
        if (values.isEmpty()) throw new IllegalArgumentException("compound shapes must not be empty");
        return new GenericRigidBody(SHAPE_BOX, compoundBounds(values), values, mass, false);
    }

    /** Kinematic variant of any shape; pose follows external driving each step. */
    public static GenericRigidBody kinematic(int shape, Vector3f shapeSize) {
        return new GenericRigidBody(shape, new Vector3f(shapeSize), 0f, true);
    }

    /** Kinematic compound body made from local-space Box3D primitives. */
    public static GenericRigidBody kinematic(List<? extends BodyShape> shapes) {
        List<BodyShape> values = List.copyOf(Objects.requireNonNull(shapes, "shapes"));
        if (values.isEmpty()) throw new IllegalArgumentException("compound shapes must not be empty");
        return new GenericRigidBody(SHAPE_BOX, compoundBounds(values), values, 0f, true);
    }

    private static Vector3f compoundBounds(List<BodyShape> shapes) {
        float radius = 0f;
        for (BodyShape shape : shapes) radius = Math.max(radius, shape.boundingRadius());
        return new Vector3f(radius);
    }

    // ------------------------------------------------------------------
    // Fluent configuration
    // ------------------------------------------------------------------

    public GenericRigidBody at(float x, float y, float z) {
        pose.position.set(x, y, z);
        previousPose.set(pose);
        interpolationPose.set(pose);
        return this;
    }

    public GenericRigidBody at(Vector3f position) {
        Objects.requireNonNull(position, "position");
        return at(position.x, position.y, position.z);
    }

    public GenericRigidBody rotation(Quaternionf rotation) {
        pose.rotation.set(Objects.requireNonNull(rotation, "rotation")).normalize();
        previousPose.set(pose);
        interpolationPose.set(pose);
        return this;
    }

    public GenericRigidBody velocity(Vector3f linearVelocity) {
        this.linearVelocity.set(Objects.requireNonNull(linearVelocity, "linearVelocity"));
        return this;
    }

    public GenericRigidBody angularVelocity(Vector3f angularVelocity) {
        this.angularVelocity.set(Objects.requireNonNull(angularVelocity, "angularVelocity"));
        return this;
    }

    public GenericRigidBody friction(float friction) {
        this.friction = Math.max(0f, friction);
        return this;
    }

    public GenericRigidBody restitution(float restitution) {
        this.restitution = Math.clamp(restitution, 0f, 1f);
        return this;
    }

    /** Collision group index (0-15) and mask of groups to never collide with. */
    public GenericRigidBody filter(int collisionGroup, int nonCollisionMask) {
        if (collisionGroup < 0 || collisionGroup > 15) {
            throw new IllegalArgumentException("collisionGroup must be within [0, 15]");
        }
        this.collisionGroup = collisionGroup;
        this.nonCollisionMask = nonCollisionMask;
        return this;
    }

    public GenericRigidBody damping(float linearDamping, float angularDamping) {
        this.linearDamping = Math.max(0f, linearDamping);
        this.angularDamping = Math.max(0f, angularDamping);
        return this;
    }

    /** Applies an arbitrary mutation after waking the island. */
    public GenericRigidBody edit(Consumer<GenericRigidBody> mutation) {
        mutation.accept(this);
        wake.run();
        return this;
    }

    // ------------------------------------------------------------------
    // Runtime control
    // ------------------------------------------------------------------

    public void teleport(Vector3f position, Quaternionf rotation) {
        wake.run();
        pose.position.set(Objects.requireNonNull(position, "position"));
        pose.rotation.set(Objects.requireNonNull(rotation, "rotation")).normalize();
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

    /** Wires wake callbacks; called by the owning world on registration. */
    public void wireWake(Runnable wakeCallback) {
        this.wake = Objects.requireNonNull(wakeCallback, "wakeCallback");
    }

    // ------------------------------------------------------------------
    // SimBody contract
    // ------------------------------------------------------------------

    @Override public int shape() { return shape; }
    @Override public Vector3f shapeSizeRef() { return shapeSize; }
    @Override public List<BodyShape> collisionShapes() { return collisionShapes; }
    @Override public float friction() { return friction; }
    @Override public float restitution() { return restitution; }
    @Override public int collisionGroup() { return collisionGroup; }
    @Override public int nonCollisionMask() { return nonCollisionMask; }
    @Override public boolean kinematic() { return kinematic; }
    @Override public float linearDamping() { return linearDamping; }
    @Override public float angularDamping() { return angularDamping; }
    @Override public float inverseLinearMass() { return inverseLinearMass; }
    @Override public Vector3f positionRef() { return pose.position; }
    @Override public Vector3f previousPositionRef() { return previousPose.position; }
    @Override public Quaternionf rotationRef() { return pose.rotation; }
    @Override public Quaternionf previousRotationRef() { return previousPose.rotation; }
    @Override public Vector3f interpolationPositionRef() { return interpolationPose.position; }
    @Override public Quaternionf interpolationRotationRef() { return interpolationPose.rotation; }
    @Override public Vector3f linearVelocityRef() { return linearVelocity; }
    @Override public Vector3f angularVelocityRef() { return angularVelocity; }

    public Vector3f position() { return new Vector3f(pose.position); }
    public Quaternionf rotation() { return new Quaternionf(pose.rotation); }
}
