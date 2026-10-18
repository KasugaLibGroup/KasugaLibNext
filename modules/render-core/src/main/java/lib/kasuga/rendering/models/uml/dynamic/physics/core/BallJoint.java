package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Java-side description of a Box3D spherical joint. Constraint solving is
 * performed exclusively by Box3D.
 */
public class BallJoint {
    protected final SimBody bodyA;
    protected final SimBody bodyB;
    protected final Pose localA;
    protected final Pose localB;
    private final Vector3f positionMin;
    private final Vector3f positionMax;
    private final Vector3f rotationMinimum;
    private final Vector3f rotationMaximum;
    private final Vector3f springLinear;
    private final Vector3f springAngular;
    private final RotationLimiter rotationLimiter;
    private final Vector3f twistAxis;

    public BallJoint(SimBody bodyA, SimBody bodyB, Pose localA, Pose localB,
                     Vector3f positionMin, Vector3f positionMax,
                     Vector3f rotationMinimum, Vector3f rotationMaximum,
                     Vector3f springLinear, Vector3f springAngular,
                     RotationLimiter rotationLimiter, Vector3f twistAxis) {
        this.bodyA = Objects.requireNonNull(bodyA, "bodyA");
        this.bodyB = Objects.requireNonNull(bodyB, "bodyB");
        this.localA = new Pose(localA.position, localA.rotation);
        this.localB = new Pose(localB.position, localB.rotation);
        this.positionMin = new Vector3f(positionMin);
        this.positionMax = new Vector3f(positionMax);
        this.rotationMinimum = new Vector3f(rotationMinimum);
        this.rotationMaximum = new Vector3f(rotationMaximum);
        this.springLinear = new Vector3f(springLinear);
        this.springAngular = new Vector3f(springAngular);
        this.rotationLimiter = rotationLimiter;
        this.twistAxis = new Vector3f(twistAxis).normalize();
    }

    /** Authored-limit metadata used by the Box3D adapter and diagnostics. */
    public interface RotationLimiter {
        Quaternionf clamp(Quaternionf relativeRotation, Vector3f twistAxis);
        float violation(Quaternionf relativeRotation, Vector3f twistAxis);
        /** Legacy profile metadata; Box3D owns limit correction strength. */
        float stiffness();
        default float box3dConeAngle() { return Float.NaN; }
        default float box3dLowerTwistAngle() { return Float.NaN; }
        default float box3dUpperTwistAngle() { return Float.NaN; }
    }

    public SimBody bodyA() { return bodyA; }
    public SimBody bodyB() { return bodyB; }
    public Pose localFrameA() { return new Pose(localA.position, localA.rotation); }
    public Pose localFrameB() { return new Pose(localB.position, localB.rotation); }
    public Vector3f positionMinimum() { return new Vector3f(positionMin); }
    public Vector3f positionMaximum() { return new Vector3f(positionMax); }
    public Vector3f rotationMinimum() { return new Vector3f(rotationMinimum); }
    public Vector3f rotationMaximum() { return new Vector3f(rotationMaximum); }
    public Vector3f linearSpring() { return new Vector3f(springLinear); }
    public Vector3f angularSpring() { return new Vector3f(springAngular); }
    public Vector3f twistAxis() { return new Vector3f(twistAxis); }
    public RotationLimiter rotationLimiter() { return rotationLimiter; }

    public Vector3f relativePosition() {
        return Frames.relative(frameOf(bodyA, localA), frameOf(bodyB, localB)).position;
    }

    public Vector3f relativeRotation() {
        return Frames.euler(Frames.relative(frameOf(bodyA, localA), frameOf(bodyB, localB)).rotation);
    }

    public float angularLimitViolation() {
        Quaternionf rotation = Frames.relative(frameOf(bodyA, localA), frameOf(bodyB, localB)).rotation;
        if (rotationLimiter != null) {
            return Math.max(0f, rotationLimiter.violation(canonical(rotation), twistAxis));
        }
        Vector3f angles = Frames.euler(rotation);
        return Math.max(
                Math.max(limitViolation(angles.x, rotationMinimum.x, rotationMaximum.x),
                        limitViolation(angles.y, rotationMinimum.y, rotationMaximum.y)),
                limitViolation(angles.z, rotationMinimum.z, rotationMaximum.z));
    }

    private static Pose frameOf(SimBody body, Pose local) {
        return Frames.compose(new Pose(body.positionRef(), body.rotationRef()), local);
    }

    private static float limitViolation(float value, float first, float second) {
        float minimum = Math.min(first, second);
        float maximum = Math.max(first, second);
        return Math.max(0f, Math.max(minimum - value, value - maximum));
    }

    private static Quaternionf canonical(Quaternionf rotation) {
        Quaternionf result = new Quaternionf(rotation).normalize();
        return result.w < 0f ? result.mul(-1f) : result;
    }
}
