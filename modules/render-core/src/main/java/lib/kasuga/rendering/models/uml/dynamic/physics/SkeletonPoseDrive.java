package lib.kasuga.rendering.models.uml.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.Set;

/** Bounded fixed-step pose forces; native Box3D remains the sole dynamics solver. */
public final class SkeletonPoseDrive {
    private SkeletonPoseDrive() {}

    public static void apply(SkeletonRagdoll physics, Set<String> bodyBones, float frequencyHz,
                             float dampingRatio, float maximumForce, float maximumTorque, float weight) {
        if (weight <= 0f) return;
        float omega = (float)(2.0 * Math.PI) * frequencyHz;
        float spring = omega * omega;
        float damping = 2f * dampingRatio * omega;
        for (SkeletonRagdoll.Body body : physics.bodies()) {
            if (body.inverseLinearMass() <= 0f || body.bone() == null
                    || (!bodyBones.isEmpty() && !bodyBones.contains(body.bone().getName()))) continue;
            float mass = 1f / body.inverseLinearMass();
            Frames.Pose target = physics.animationTarget(body);

            Vector3f force = new Vector3f(target.position).sub(body.position())
                    .mul(spring).fma(-damping, body.linearVelocity())
                    .mul(mass * weight);
            clampLength(force, maximumForce);
            physics.applyForce(body, force);

            Quaternionf rotationError = new Quaternionf(target.rotation)
                    .mul(new Quaternionf(body.rotation()).invert()).normalize();
            Vector3f angularError = Frames.quaternionAxis(rotationError)
                    .mul(Frames.quaternionAngle(rotationError));
            // Compatibility with existing ActiveRagdollModule tuning: this is a mass-scaled
            // angular drive, not inertia-normalized acceleration. maximumTorque bounds small rigs.
            Vector3f torque = angularError.mul(spring)
                    .fma(-damping, body.angularVelocity())
                    .mul(mass * weight);
            clampLength(torque, maximumTorque);
            physics.applyTorque(body, torque);
        }
    }

    private static void clampLength(Vector3f value, float maximum) {
        float lengthSquared = value.lengthSquared();
        if (lengthSquared > maximum * maximum) value.mul(maximum / (float)Math.sqrt(lengthSquared));
    }
}
