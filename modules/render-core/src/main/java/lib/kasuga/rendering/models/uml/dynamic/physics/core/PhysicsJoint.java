package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;

import java.util.Objects;

/**
 * Base type for generic Box3D joints managed through {@link RigidBodyWorld}:
 * weld, distance, revolute and prismatic. Constraint solving happens
 * exclusively inside native Box3D; subclasses only carry authored settings
 * and forward live tuning calls to their bound native handle.
 */
public abstract sealed class PhysicsJoint
        permits WeldJoint, DistanceJoint, RevoluteJoint, PrismaticJoint {
    private final SimBody bodyA;
    private final SimBody bodyB;
    private final Pose localA;
    private final Pose localB;
    /** Positive constraint solver frequency override; zero keeps the engine default. */
    private final float constraintHertz;
    private final float constraintDampingRatio;
    private final boolean collideConnected;
    /** Packed native handle, written by the owning backend. Zero while unbound. */
    long nativeId;

    protected PhysicsJoint(SimBody bodyA, SimBody bodyB,
                           Pose localA, Pose localB,
                           float constraintHertz, float constraintDampingRatio,
                           boolean collideConnected) {
        this.bodyA = Objects.requireNonNull(bodyA, "bodyA");
        this.bodyB = Objects.requireNonNull(bodyB, "bodyB");
        this.localA = new Pose(localA.position, localA.rotation);
        this.localB = new Pose(localB.position, localB.rotation);
        this.constraintHertz = clampPositive(constraintHertz);
        this.constraintDampingRatio = clampPositive(constraintDampingRatio);
        this.collideConnected = collideConnected;
    }

    public final SimBody bodyA() { return bodyA; }
    public final SimBody bodyB() { return bodyB; }
    public final Pose localFrameA() { return new Pose(localA.position, localA.rotation); }
    public final Pose localFrameB() { return new Pose(localB.position, localB.rotation); }
    public final float constraintHertz() { return constraintHertz; }
    public final float constraintDampingRatio() { return constraintDampingRatio; }
    public final boolean collideConnected() { return collideConnected; }

    /** True once the owning world created the native joint. */
    public final boolean isBound() { return nativeId != 0L; }

    protected static float clampPositive(float value) {
        return Float.isFinite(value) && value > 0f ? value : 0f;
    }

    protected static Quaternionf identity() {
        return new Quaternionf();
    }
}
