package lib.kasuga.rendering.models.uml.framework.schedule;

/**
 * Per-instance visibility policy. Instances are identified by object identity.
 * Policy and distance gates are independent; changing mode preserves distance.
 * Implementations must serialize tick-thread writes and render-thread reads.
 * Frustum tests, animation sampling and drawing remain backend responsibilities.
 */
public interface RenderScheduler<I> {
    void setMode(I instance, RenderScheduleMode mode);
    RenderScheduleMode mode(I instance);
    /** Selects MANUAL mode and sets its persistent visibility switch. */
    void setVisible(I instance, boolean visible);
    void markRenderedThisFrame(I instance);
    /**
     * Called once before the first model pass, after the host's render callbacks.
     * Retains collected marks across all passes, expiring them at the next flip
     * unless marked again. Marks made after a flip are immediately visible too.
     */
    void flipFrame();
    /** Start a separate host view, dropping only visibility marks, preserving policies. */
    void clearFrameMarks();
    boolean wasMarkedThisFrame(I instance);
    boolean shouldRender(I instance);
    /** Distance in host world units; non-positive values disable this gate. */
    void setMaxRenderDistance(I instance, float distance);
    float maxRenderDistance(I instance);
    boolean withinRenderDistance(I instance, float cameraDistanceSquared);
    /** Removes policy and both sets of frame marks; null is a no-op. */
    void detach(I instance);
    void resetAll();
}
