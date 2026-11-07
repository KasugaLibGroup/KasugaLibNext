package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Drives dynamic Box3D bodies toward their same-frame animation/IK targets.
 *
 * <p>This is an active-ragdoll controller, not a second physics solver: it
 * computes spring forces and torques against the freshly evaluated kinematic
 * targets, each fixed step, then Box3D performs all integration, collision and joint solving
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
        if (!enabled) return;
        var physics = loop.getInstance().getSkeletonInstance().getRelaxation();
        if (physics != null && physics.enabled()) physics.captureAnimationTarget();
    }

    @Override
    public void beforePhysicsStep(Model model, ModelTickLoop loop) {
        if (!enabled) return;
        var physics = loop.getInstance().getSkeletonInstance().getRelaxation();
        if (physics == null || !physics.enabled()) return;
        Settings value = settings;
        if (value.weight <= 0f) return;
        lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonPoseDrive.apply(physics, bodyBones,
                value.frequencyHz, value.dampingRatio, value.maximumForce, value.maximumTorque, value.weight);
    }

    @Override
    public void destroy(Model model) {}

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
