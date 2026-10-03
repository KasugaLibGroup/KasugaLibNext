package lib.kasuga.rendering.models.uml.framework.schedule;

/** Visibility authority, independent of the host's renderer API. */
public enum RenderScheduleMode {
    ALWAYS,
    MANUAL,
    /** A host renderer must mark the instance in each visible frame. */
    HOST_RENDERER
}
