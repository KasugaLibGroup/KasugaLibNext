package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** A render-time projection snapshot. Coordinates are GUI pixels, with the origin at the top left. */
public final class ScreenPointerProjection {
    private final Matrix4f inverseProjection;
    private final double width;
    private final double height;

    public ScreenPointerProjection(Matrix4fc projection, double width, double height) {
        if (!(width > 0) || !(height > 0) || !Double.isFinite(width) || !Double.isFinite(height)
                || !projection.isFinite() || projection.determinant() == 0) {
            throw new IllegalArgumentException("A finite, invertible projection and positive viewport are required");
        }
        inverseProjection = new Matrix4f(projection).invert();
        if (!inverseProjection.isFinite()) throw new IllegalArgumentException("Projection inverse is not finite");
        this.width = width;
        this.height = height;
    }

    /**
     * Unprojects two clip-space points into render view space. The ray starts on the near plane,
     * works with perspective and orthographic cameras, and includes projection zoom/bobbing.
     * Transform both outputs by the inverse view matrix to obtain a world-space ray.
     */
    public boolean ray(double mouseX, double mouseY, Vector3f origin, Vector3f direction) {
        if (!Double.isFinite(mouseX) || !Double.isFinite(mouseY)) return false;
        float x = (float) (2 * mouseX / width - 1);
        float y = (float) (1 - 2 * mouseY / height);
        inverseProjection.transformProject(origin.set(x, y, -1));
        // An interior depth avoids the infinite far plane used by some projections.
        inverseProjection.transformProject(direction.set(x, y, 0));
        direction.sub(origin);
        if (!origin.isFinite() || !direction.isFinite() || direction.lengthSquared() < 1e-12f) return false;
        direction.normalize();
        return true;
    }
}
