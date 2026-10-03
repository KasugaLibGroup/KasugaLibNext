package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Hinge around the shared joint frame X axis: doors, lids, flaps, windmills.
 * Optional return spring, angle limits (clamped to ±0.99π by Box3D) and a
 * constant-speed motor.
 */
public final class RevoluteJoint extends PhysicsJoint {
    private final float targetAngle;
    private final boolean enableSpring;
    private final float springHertz;
    private final float springDampingRatio;
    private final boolean enableLimit;
    private final float lowerAngle;
    private final float upperAngle;
    private final boolean enableMotor;
    private final float maxMotorTorque;
    private final float motorSpeed;

    private RevoluteJoint(Builder builder) {
        super(builder.bodyA, builder.bodyB, builder.localA, builder.localB,
                builder.constraintHertz, builder.constraintDampingRatio, builder.collideConnected);
        this.targetAngle = builder.targetAngle;
        this.enableSpring = builder.enableSpring;
        this.springHertz = clampPositive(builder.springHertz);
        this.springDampingRatio = clampPositive(builder.springDampingRatio);
        this.enableLimit = builder.enableLimit;
        this.lowerAngle = clampLimit(builder.lowerAngle);
        this.upperAngle = clampLimit(Math.max(this.lowerAngle, builder.upperAngle));
        this.enableMotor = builder.enableMotor;
        this.maxMotorTorque = clampPositive(builder.maxMotorTorque);
        this.motorSpeed = builder.motorSpeed;
    }

    public static Builder between(SimBody bodyA, SimBody bodyB) {
        return new Builder(bodyA, bodyB);
    }

    public float targetAngle() { return targetAngle; }
    public boolean springEnabled() { return enableSpring; }
    public float springHertz() { return springHertz; }
    public float springDampingRatio() { return springDampingRatio; }
    public boolean limitEnabled() { return enableLimit; }
    public float lowerAngle() { return lowerAngle; }
    public float upperAngle() { return upperAngle; }
    public boolean motorEnabled() { return enableMotor; }
    public float maxMotorTorque() { return maxMotorTorque; }
    public float motorSpeed() { return motorSpeed; }

    public void setLimits(boolean enable, float lowerAngle, float upperAngle) {
        NativeBox3D.setRevoluteJointLimits(nativeId(), enable, clampLimit(lowerAngle), clampLimit(upperAngle));
    }
    /** Positive speed turns bodyB positively around the hinge axis. */
    public void setMotor(boolean enable, float speed, float maxTorque) {
        NativeBox3D.setRevoluteJointMotor(nativeId(), enable, speed, maxTorque);
    }
    public void setSpring(boolean enable, float hertz, float dampingRatio, float targetAngle) {
        NativeBox3D.setRevoluteJointSpring(nativeId(), enable, hertz, dampingRatio, targetAngle);
    }

    private long nativeId() {
        if (!isBound()) throw new IllegalStateException("joint is not bound to a world");
        return nativeId;
    }

    private static float clampLimit(float radians) {
        if (!Float.isFinite(radians)) return 0f;
        return Math.clamp(radians, -0.99f * (float) Math.PI, 0.99f * (float) Math.PI);
    }

    public static final class Builder {
        private final SimBody bodyA;
        private final SimBody bodyB;
        private Pose localA = new Pose(new Vector3f(), identity());
        private Pose localB = new Pose(new Vector3f(), identity());
        private float constraintHertz;
        private float constraintDampingRatio;
        private boolean collideConnected;
        private float targetAngle;
        private boolean enableSpring;
        private float springHertz;
        private float springDampingRatio = 1f;
        private boolean enableLimit;
        private float lowerAngle = -(float) Math.PI * 0.99f;
        private float upperAngle = (float) Math.PI * 0.99f;
        private boolean enableMotor;
        private float maxMotorTorque;
        private float motorSpeed;

        private Builder(SimBody bodyA, SimBody bodyB) {
            this.bodyA = bodyA;
            this.bodyB = bodyB;
        }

        public Builder localFrameA(Pose pose) { this.localA = pose; return this; }
        public Builder localFrameB(Pose pose) { this.localB = pose; return this; }
        public Builder constraintTuning(float hertz, float dampingRatio) {
            this.constraintHertz = hertz;
            this.constraintDampingRatio = dampingRatio;
            return this;
        }
        public Builder collideConnected(boolean value) { this.collideConnected = value; return this; }
        /** Rotates the hinge frame so its X axis aligns with the supplied world axis. */
        public Builder hingeAxis(Vector3f worldAxis) {
            Quaternionf rotation = new Quaternionf().rotationTo(
                    new Vector3f(1f, 0f, 0f), new Vector3f(worldAxis).normalize());
            this.localA = new Pose(new Vector3f(localA.position), rotation);
            this.localB = new Pose(new Vector3f(localB.position), rotation);
            return this;
        }
        public Builder spring(boolean enable, float hertz, float dampingRatio, float targetAngle) {
            this.enableSpring = enable;
            this.springHertz = hertz;
            this.springDampingRatio = dampingRatio;
            this.targetAngle = targetAngle;
            return this;
        }
        public Builder limits(boolean enable, float lowerAngle, float upperAngle) {
            this.enableLimit = enable;
            this.lowerAngle = lowerAngle;
            this.upperAngle = upperAngle;
            return this;
        }
        public Builder motor(boolean enable, float speed, float maxTorque) {
            this.enableMotor = enable;
            this.motorSpeed = speed;
            this.maxMotorTorque = maxTorque;
            return this;
        }

        public RevoluteJoint build() { return new RevoluteJoint(this); }
    }
}
