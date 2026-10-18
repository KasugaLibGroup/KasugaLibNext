package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rope/spring between two local anchor points. Supports a frequency spring,
 * min/max length limits and a winch motor — enough to express hanging
 * payloads, elastic tethers and retractable ropes.
 */
public final class DistanceJoint extends PhysicsJoint {
    private final float length;
    private final boolean enableSpring;
    private final float hertz;
    private final float dampingRatio;
    private final boolean enableLimit;
    private final float minLength;
    private final float maxLength;
    private final boolean enableMotor;
    private final float motorSpeed;
    private final float maxMotorForce;

    private DistanceJoint(Builder builder) {
        super(builder.bodyA, builder.bodyB, builder.localA, builder.localB,
                builder.constraintHertz, builder.constraintDampingRatio, builder.collideConnected);
        this.length = Math.max(0f, builder.length);
        this.enableSpring = builder.enableSpring;
        this.hertz = clampPositive(builder.hertz);
        this.dampingRatio = clampPositive(builder.dampingRatio);
        this.enableLimit = builder.enableLimit;
        this.minLength = Math.max(0f, builder.minLength);
        this.maxLength = Math.max(this.minLength, builder.maxLength);
        this.enableMotor = builder.enableMotor;
        this.motorSpeed = builder.motorSpeed;
        this.maxMotorForce = clampPositive(builder.maxMotorForce);
    }

    public static Builder between(SimBody bodyA, SimBody bodyB) {
        return new Builder(bodyA, bodyB);
    }

    public float length() { return length; }
    public boolean springEnabled() { return enableSpring; }
    public float hertz() { return hertz; }
    public float dampingRatio() { return dampingRatio; }
    public boolean limitEnabled() { return enableLimit; }
    public float minLength() { return minLength; }
    public float maxLength() { return maxLength; }
    public boolean motorEnabled() { return enableMotor; }
    public float motorSpeed() { return motorSpeed; }
    public float maxMotorForce() { return maxMotorForce; }

    public void setLength(float value) { NativeBox3D.setDistanceJointLength(nativeId(), value); }
    public void setSpring(boolean enable, float hertz, float dampingRatio) {
        NativeBox3D.setDistanceJointSpring(nativeId(), enable, hertz, dampingRatio);
    }
    public void setLimits(boolean enable, float minLength, float maxLength) {
        NativeBox3D.setDistanceJointLimits(nativeId(), enable, minLength, maxLength);
    }
    /** Winch: positive speed reels bodyB toward bodyA. */
    public void setMotor(boolean enable, float speed, float maxForce) {
        NativeBox3D.setDistanceJointMotor(nativeId(), enable, speed, maxForce);
    }

    private long nativeId() {
        if (!isBound()) throw new IllegalStateException("joint is not bound to a world");
        return nativeId;
    }

    public static final class Builder {
        private final SimBody bodyA;
        private final SimBody bodyB;
        private Pose localA = new Pose(new Vector3f(), identity());
        private Pose localB = new Pose(new Vector3f(), identity());
        private float constraintHertz;
        private float constraintDampingRatio;
        private boolean collideConnected;
        private float length = 1f;
        private boolean enableSpring;
        private float hertz;
        private float dampingRatio = 1f;
        private boolean enableLimit;
        private float minLength;
        private float maxLength = 1f;
        private boolean enableMotor;
        private float motorSpeed;
        private float maxMotorForce;

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
        public Builder length(float value) { this.length = value; return this; }
        public Builder spring(boolean enable, float hertz, float dampingRatio) {
            this.enableSpring = enable;
            this.hertz = hertz;
            this.dampingRatio = dampingRatio;
            return this;
        }
        public Builder limits(boolean enable, float minLength, float maxLength) {
            this.enableLimit = enable;
            this.minLength = minLength;
            this.maxLength = maxLength;
            return this;
        }
        public Builder motor(boolean enable, float speed, float maxForce) {
            this.enableMotor = enable;
            this.motorSpeed = speed;
            this.maxMotorForce = maxForce;
            return this;
        }

        public DistanceJoint build() { return new DistanceJoint(this); }
    }
}
