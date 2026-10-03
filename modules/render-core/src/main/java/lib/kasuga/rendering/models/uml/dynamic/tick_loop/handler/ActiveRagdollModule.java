package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Drives dynamic Box3D bodies toward their same-frame animation/IK targets.
 *
 * <p>This is an active-ragdoll controller, not a second physics solver: it
 * computes spring forces and torques against the freshly evaluated kinematic
 * targets, then Box3D performs all integration, collision and joint solving
 * during the physics stage. An empty body-name set controls every dynamic
 * body; otherwise only the named bone bodies are affected.</p>
 *
 * <p>Mount with {@code loop.addPostIk(...)} (equivalently addBefore
 * {@link ModelTickLoop#SLOT_PHYSICS}) so forces are enqueued before the
 * physics stage integrates them.</p>
 */
public final class ActiveRagdollModule implements ModelTickLoopModule {
    private final Set<String> bodyBones;
    private volatile Settings settings;
    private volatile boolean enabled = true;

    public ActiveRagdollModule(float frequencyHz, float dampingRatio,
                               float maximumForce, float maximumTorque) {
        this(Set.of(), frequencyHz, dampingRatio, maximumForce, maximumTorque, 1f);
    }

    public ActiveRagdollModule(Collection<String> bodyBones,
                               float frequencyHz, float dampingRatio,
                               float maximumForce, float maximumTorque, float weight) {
        this.bodyBones = Set.copyOf(Objects.requireNonNull(bodyBones, "bodyBones"));
        setSettings(frequencyHz, dampingRatio, maximumForce, maximumTorque, weight);
    }

    public Set<String> bodyBones() { return bodyBones; }
    public Settings settings() { return settings; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void setSettings(float frequencyHz, float dampingRatio,
                            float maximumForce, float maximumTorque, float weight) {
        settings = new Settings(frequencyHz, dampingRatio, maximumForce, maximumTorque, weight);
    }

    public void setWeight(float weight) {
        Settings value = settings;
        settings = new Settings(value.frequencyHz, value.dampingRatio,
                value.maximumForce, value.maximumTorque, weight);
    }

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        Settings value = settings;
        if (!enabled) return;
        MmdRagdoll physics = loop.getInstance().getRagdoll();
        if (physics == null || !physics.enabled()) return;
        // Refresh the kinematic targets so forces chase THIS tick's post-IK pose,
        // not a stale snapshot from the previous step.
        physics.evaluateAnimationTarget();

        float omega = (float)(2.0 * Math.PI) * value.frequencyHz;
        float spring = omega * omega;
        float damping = 2f * value.dampingRatio * omega;
        for (MmdRagdoll.Body body : physics.bodies()) {
            if (body.inverseLinearMass() <= 0f || body.bone() == null
                    || (!bodyBones.isEmpty() && !bodyBones.contains(body.bone().getName()))) continue;
            float mass = 1f / body.inverseLinearMass();
            Frames.Pose target = physics.animationTarget(body);

            Vector3f force = new Vector3f(target.position).sub(body.position())
                    .mul(spring).fma(-damping, body.linearVelocity())
                    .mul(mass * value.weight);
            clampLength(force, value.maximumForce);
            physics.applyForce(body, force);

            Quaternionf rotationError = new Quaternionf(target.rotation)
                    .mul(new Quaternionf(body.rotation()).invert()).normalize();
            Vector3f angularError = Frames.quaternionAxis(rotationError)
                    .mul(Frames.quaternionAngle(rotationError));
            Vector3f torque = angularError.mul(spring)
                    .fma(-damping, body.angularVelocity())
                    .mul(mass * value.weight);
            clampLength(torque, value.maximumTorque);
            physics.applyTorque(body, torque);
        }
    }

    @Override
    public void destroy(Model model) {}

    private static void clampLength(Vector3f value, float maximum) {
        float lengthSquared = value.lengthSquared();
        if (lengthSquared > maximum * maximum) value.mul(maximum / (float)Math.sqrt(lengthSquared));
    }

    public record Settings(float frequencyHz, float dampingRatio,
                           float maximumForce, float maximumTorque, float weight) {
        public Settings {
            if (!Float.isFinite(frequencyHz) || frequencyHz < 0f
                    || !Float.isFinite(dampingRatio) || dampingRatio < 0f
                    || !Float.isFinite(maximumForce) || maximumForce < 0f
                    || !Float.isFinite(maximumTorque) || maximumTorque < 0f
                    || !Float.isFinite(weight) || weight < 0f || weight > 1f) {
                throw new IllegalArgumentException("active ragdoll settings must be finite and non-negative; weight must be within [0, 1]");
            }
        }
    }
}
