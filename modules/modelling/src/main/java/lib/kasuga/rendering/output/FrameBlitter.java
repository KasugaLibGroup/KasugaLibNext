package lib.kasuga.rendering.output;

/** Draws a completed view into the currently bound output framebuffer. */
@FunctionalInterface
public interface FrameBlitter {
    void draw(int width, int height);
}
