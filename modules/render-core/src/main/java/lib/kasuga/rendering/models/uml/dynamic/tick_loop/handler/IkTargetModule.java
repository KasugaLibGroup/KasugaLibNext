package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Writes a world-space position target for one PMX IK controller into the
 * skeleton as a transient, single-tick target. Mounted pre-IK via
 * {@code loop.addPreIk(...)}; the IK stage consumes (and the next tick's
 * preamble clears) whatever this module publishes.
 */
public final class IkTargetModule implements ModelTickLoopModule {
    private final String controllerBone;
    private volatile Target target;

    public IkTargetModule(String controllerBone, Vector3f worldTarget) {
        this(controllerBone, worldTarget, 1f);
    }

    public IkTargetModule(String controllerBone, Vector3f worldTarget, float weight) {
        if (controllerBone == null || controllerBone.isBlank()) {
            throw new IllegalArgumentException("controller bone must not be blank");
        }
        this.controllerBone = controllerBone;
        setTarget(worldTarget, weight);
    }

    public String controllerBone() { return controllerBone; }
    public Vector3f target() { return new Vector3f(target.position); }
    public float weight() { return target.weight; }
    public boolean enabled() { return target.enabled; }

    public void setTarget(Vector3f worldTarget) { setTarget(worldTarget, weight()); }

    public void setTarget(Vector3f worldTarget, float weight) {
        Vector3f position = new Vector3f(Objects.requireNonNull(worldTarget, "worldTarget"));
        if (!position.isFinite() || !Float.isFinite(weight) || weight < 0f || weight > 1f) {
            throw new IllegalArgumentException("IK target must be finite and weight within [0, 1]");
        }
        Target previous = target;
        target = new Target(position, weight, previous == null || previous.enabled);
    }

    public void setEnabled(boolean enabled) {
        Target value = target;
        target = new Target(value.position, value.weight, enabled);
    }

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        Target value = target;
        if (value.enabled) {
            loop.getInstance().getSkeletonInstance()
                    .setFrameIkTarget(controllerBone, value.position, value.weight);
        }
    }

    @Override
    public void destroy(Model model) {}

    private record Target(Vector3f position, float weight, boolean enabled) {
        private Target {
            position = new Vector3f(position);
        }
    }
}
