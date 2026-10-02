package lib.kasuga.rendering.models.uml.framework.schedule;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Identity-based, synchronized policy state extracted from the model renderer. */
public final class DefaultRenderScheduler<I> implements RenderScheduler<I> {
    private final Object lock = new Object();
    private final Map<I, Policy> policies = new IdentityHashMap<>();
    private Set<I> markedThisFrame = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<I> consumedMarks = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Selects who controls visibility for this instance. */
    public void setMode(I instance, RenderScheduleMode mode) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(mode, "mode");
        synchronized (lock) {
            policies.computeIfAbsent(instance, ignored -> new Policy()).mode = mode;
        }
    }

    public RenderScheduleMode mode(I instance) {
        synchronized (lock) {
            Policy policy = policies.get(instance);
            return policy == null ? RenderScheduleMode.ALWAYS : policy.mode;
        }
    }

    /** MANUAL mode only: hard show/hide switch. */
    public void setVisible(I instance, boolean visible) {
        Objects.requireNonNull(instance, "instance");
        synchronized (lock) {
            Policy policy = policies.computeIfAbsent(instance, ignored -> new Policy());
            policy.mode = RenderScheduleMode.MANUAL;
            policy.manualVisible = visible;
        }
    }

    /**
     * HOST_RENDERER mode only: called from inside a host renderer's
     * {@code render()} — proof that host passed its own culling this frame.
     */
    public void markRenderedThisFrame(I instance) {
        Objects.requireNonNull(instance, "instance");
        synchronized (lock) {
            markedThisFrame.add(instance);
        }
    }

    /** Per-instance view-distance cap in host world units; zero disables distance culling. */
    public void setMaxRenderDistance(I instance, float blocks) {
        Objects.requireNonNull(instance, "instance");
        if (!Float.isFinite(blocks)) throw new IllegalArgumentException("Distance must be finite");
        synchronized (lock) {
            if (!(blocks > 0f)) {
                Policy policy = policies.get(instance);
                if (policy != null) policy.maxRenderDistance = 0f;
                return;
            }
            policies.computeIfAbsent(instance, ignored -> new Policy()).maxRenderDistance = blocks;
        }
    }

    public float maxRenderDistance(I instance) {
        synchronized (lock) {
            Policy policy = policies.get(instance);
            return policy == null ? 0f : policy.maxRenderDistance;
        }
    }

    /**
     * Consumes the marks accumulated during the host pass. Called once at
     * the global pipeline's frame start — the host render pass has already run by then, so its decisions are preserved in the
     * consumed snapshot while the buffer clears for the next frame.
     */
    public void flipFrame() {
        synchronized (lock) {
            Set<I> swap = consumedMarks;
            consumedMarks = markedThisFrame;
            markedThisFrame = swap;
            markedThisFrame.clear();
        }
    }

    public void clearFrameMarks() {
        synchronized (lock) {
            markedThisFrame.clear();
            consumedMarks.clear();
        }
    }

    /** Whether a host renderer marked the instance during this frame's pass. */
    public boolean wasMarkedThisFrame(I instance) {
        synchronized (lock) {
            return consumedMarks.contains(instance) || markedThisFrame.contains(instance);
        }
    }

    /** Combined per-frame decision for one instance. */
    public boolean shouldRender(I instance) {
        synchronized (lock) {
            Policy policy = policies.get(instance);
            if (policy == null) return true; // ALWAYS
            return switch (policy.mode) {
                case ALWAYS -> true;
                case MANUAL -> policy.manualVisible;
                case HOST_RENDERER -> consumedMarks.contains(instance)
                        || markedThisFrame.contains(instance);
            };
        }
    }

    /** Distance gate evaluated against the camera position. */
    public boolean withinRenderDistance(I instance, float cameraDistanceSquared) {
        float maximum;
        synchronized (lock) {
            Policy policy = policies.get(instance);
            maximum = policy == null ? 0f : policy.maxRenderDistance;
        }
        return !(maximum > 0f) || cameraDistanceSquared <= maximum * maximum;
    }

    /** Drops all scheduling state for an instance (called when it unmounts). */
    public void detach(I instance) {
        if (instance == null) return;
        synchronized (lock) {
            policies.remove(instance);
            markedThisFrame.remove(instance);
            consumedMarks.remove(instance);
        }
    }

    public void resetAll() {
        synchronized (lock) {
            policies.clear();
            markedThisFrame.clear();
            consumedMarks.clear();
        }
    }

    private static final class Policy {
        private RenderScheduleMode mode = RenderScheduleMode.ALWAYS;
        private boolean manualVisible = true;
        private float maxRenderDistance;
    }
}
