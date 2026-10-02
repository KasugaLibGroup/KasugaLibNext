package lib.kasuga.rendering.output;

public enum FrameOutputMode {
    /** Deliver a frame and keep the normal screen presentation. */
    MIRROR,
    /** Suppress screen presentation only after this output succeeds. */
    OFFSCREEN_ONLY
}
