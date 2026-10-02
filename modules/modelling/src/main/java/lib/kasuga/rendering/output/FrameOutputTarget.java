package lib.kasuga.rendering.output;

/**
 * Owns destination storage and renders the final presentation into it, resizing
 * to the requested pixel dimensions. Must restore framebuffer bindings and
 * viewport on success and failure. Closing retires storage; callers never close
 * the returned resource. The router shares it read-only between view consumers,
 * which must restore any other GPU state they change.
 */
public interface FrameOutputTarget<T> extends AutoCloseable {
    T capture(FrameBlitter blitter, int width, int height);
}
