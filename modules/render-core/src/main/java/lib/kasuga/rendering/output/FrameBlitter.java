package lib.kasuga.rendering.output;

/** Draws a completed view into the output destination selected by its backend. */
@FunctionalInterface
public interface FrameBlitter {
    void draw(int width, int height);
}
