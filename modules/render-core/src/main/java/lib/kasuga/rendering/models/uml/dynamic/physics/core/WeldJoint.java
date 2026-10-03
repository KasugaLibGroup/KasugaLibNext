package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rigidly locks two bodies together with configurable softness. Zero hertz
 * (the default) is maximally stiff — a glued stack of physics blocks; higher
 * hertz values behave like a very stiff spring and allow micro-movement.
 */
public final class WeldJoint extends PhysicsJoint {
    private final float linearHertz;
    private final float angularHertz;
    private final float linearDampingRatio;
    private final float angularDampingRatio;

    private WeldJoint(Builder builder) {
        super(builder.bodyA, builder.bodyB, builder.localA, builder.localB,
                builder.constraintHertz, builder.constraintDampingRatio, builder.collideConnected);
        this.linearHertz = clampPositive(builder.linearHertz);
        this.angularHertz = clampPositive(builder.angularHertz);
        this.linearDampingRatio = clampPositive(builder.linearDampingRatio);
        this.angularDampingRatio = clampPositive(builder.angularDampingRatio);
    }

    public static Builder between(SimBody bodyA, SimBody bodyB) {
        return new Builder(bodyA, bodyB);
    }

    /** Welds at the current world-space relative pose of the two bodies. */
    public static Builder atCurrentPose(SimBody bodyA, SimBody bodyB) {
        Frames.Pose inverseA = Frames.inverse(new Frames.Pose(bodyA.positionRef(), bodyA.rotationRef()));
        Frames.Pose frameB = Frames.compose(inverseA,
                new Frames.Pose(bodyB.positionRef(), bodyB.rotationRef()));
        return new Builder(bodyA, bodyB)
                .localFrameA(new Pose(new Vector3f(), identity()))
                .localFrameB(frameB);
    }

    public float linearHertz() { return linearHertz; }
    public float angularHertz() { return angularHertz; }
    public float linearDampingRatio() { return linearDampingRatio; }
    public float angularDampingRatio() { return angularDampingRatio; }

    public static final class Builder {
        private final SimBody bodyA;
        private final SimBody bodyB;
        private Pose localA = new Pose(new Vector3f(), identity());
        private Pose localB = new Pose(new Vector3f(), identity());
        private float constraintHertz;
        private float constraintDampingRatio;
        private boolean collideConnected;
        private float linearHertz;
        private float angularHertz;
        private float linearDampingRatio = 1f;
        private float angularDampingRatio = 1f;

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
        public Builder softness(float linearHertz, float angularHertz,
                                float linearDampingRatio, float angularDampingRatio) {
            this.linearHertz = linearHertz;
            this.angularHertz = angularHertz;
            this.linearDampingRatio = linearDampingRatio;
            this.angularDampingRatio = angularDampingRatio;
            return this;
        }

        public WeldJoint build() { return new WeldJoint(this); }
    }
}
