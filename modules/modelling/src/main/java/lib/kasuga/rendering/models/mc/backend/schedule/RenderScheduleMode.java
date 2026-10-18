package lib.kasuga.rendering.models.mc.backend.schedule;

/**
 * Who decides whether a mounted model renders this frame.
 *
 * <p>Mirrors vanilla's two dispatch mechanisms:</p>
 * <ul>
 *     <li>{@link #ALWAYS} — legacy global-pipeline behavior: draw every frame.</li>
 *     <li>{@link #MANUAL} — host code toggles visibility explicitly
 *         ({@code ModelRenderScheduler.setVisible}).</li>
 *     <li>{@link #VANILLA_RENDERER} — a vanilla {@code EntityRenderer}/
 *         {@code BlockEntityRenderer} owns the decision: when the entity is
 *         culled (tracking distance) or the block entity is out of the
 *         frustum/view distance, vanilla never invokes its {@code render()},
 *         the host adapter therefore never marks the instance, and the global
 *         pipeline skips both sampling and drawing for that frame.</li>
 * </ul>
 */
public enum RenderScheduleMode {
    ALWAYS,
    MANUAL,
    VANILLA_RENDERER
}
