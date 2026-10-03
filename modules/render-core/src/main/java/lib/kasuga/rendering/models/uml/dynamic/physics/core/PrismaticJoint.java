package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Slider along the shared joint frame X axis: pistons, drawers, elevators.
 * Optional return spring toward a target translation, translation limits and
 * a constant-speed motor.
 */
public final class PrismaticJoint extends PhysicsJoint {
    private final boolean enableSpring;
    private final float springHertz;
    private final float springDampingRatio;
    private final float targetTranslation;
    private final boolean enableLimit;
    private final float lowerTranslation;
    private final float upperTranslation;
    private final boolean enableMotor;
    private final float maxMotorForce;
    private final float motorSpeed;

    private PrismaticJoint(Builder builder) {
        super(builder.bodyA, builder.bodyB, builder.localA, builder.localB,
                builder.constraintHertz, builder.constraintDampingRatio, builder.collideConnected);
        this.enableSpring = builder.enableSpring;
        this.springHertz = clampPositive(builder.springHertz);
        this.springDampingRatio = clampPositive(builder.springDampingRatio);
        this.targetTranslation = builder.targetTranslation;
        this.enableLimit = builder.enableLimit;
        this.lowerTranslation = builder.lowerTranslation;
        this.upperTranslation = Math.max(this.lowerTranslation, builder.upperTranslation);
        this.enableMotor = builder.enableMotor;
        this.maxMotorForce = clampPositive(builder.maxMotorForce);
        this.motorSpeed = builder.motorSpeed;
    }

    public static Builder between(SimBody bodyA, SimBody bodyB) {
        return new Builder(bodyA, bodyB);
    }

    public boolean springEnabled() { return enableSpring; }
    public float springHertz() { return springHertz; }
    public float springDampingRatio() { return springDampingRatio; }
    public float targetTranslation() { return targetTranslation; }
    public boolean limitEnabled() { return enableLimit; }
    public float lowerTranslation() { return lowerTranslation; }
    public float upperTranslation() { return upperTranslation; }
    public boolean motorEnabled() { return enableMotor; }
    public float maxMotorForce() { return maxMotorForce; }
    public float motorSpeed() { return motorSpeed; }

    public void setLimits(boolean enable, float lowerTranslation, float upperTranslation) {
        NativeBox3D.setPrismaticJointLimits(nativeId(), enable, lowerTranslation, upperTranslation);
    }
    /** Positive speed extends bodyB away from bodyA along the slide axis. */
    public void setMotor(boolean enable, float speed, float maxForce) {
        NativeBox3D.setPrismaticJointMotor(nativeId(), enable, speed, maxForce);
    }
    public void setSpring(boolean enable, float hertz, float dampingRatio, float targetTranslation) {
        NativeBox3D.setPrismaticJointSpring(nativeId(), enable, hertz, dampingRatio, targetTranslation);
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
        private boolean enableSpring;
        private float springHertz;
        private float springDampingRatio = 1f;
        private float targetTranslation;
        private boolean enableLimit;
        private float lowerTranslation = -1f;
        private float upperTranslation = 1f;
        private boolean enableMotor;
        private float maxMotorForce;
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
        /** Rotates the slider frame so its X axis aligns with the supplied world axis. */
        public Builder slideAxis(Vector3f worldAxis) {
            Quaternionf rotation = new Quaternionf().rotationTo(
                    new Vector3f(1f, 0f, 0f), new Vector3f(worldAxis).normalize());
            this.localA = new Pose(new Vector3f(localA.position), rotation);
            this.localB = new Pose(new Vector3f(localB.position), rotation);
            return this;
        }
        public Builder spring(boolean enable, float hertz, float dampingRatio, float targetTranslation) {
            this.enableSpring = enable;
            this.springHertz = hertz;
            this.springDampingRatio = dampingRatio;
            this.targetTranslation = targetTranslation;
            return this;
        }
        public Builder limits(boolean enable, float lowerTranslation, float upperTranslation) {
            this.enableLimit = enable;
            this.lowerTranslation = lowerTranslation;
            this.upperTranslation = upperTranslation;
            return this;
        }
        public Builder motor(boolean enable, float speed, float maxForce) {
            this.enableMotor = enable;
            this.motorSpeed = speed;
            this.maxMotorForce = maxForce;
            return this;
        }

        public PrismaticJoint build() { return new PrismaticJoint(this); }
    }
}
