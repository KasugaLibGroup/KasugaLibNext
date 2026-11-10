package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector2f;
import org.jetbrains.annotations.Nullable;

/** A render-time projection snapshot. Coordinates are GUI pixels, with the origin at the top left. */
public final class ScreenPointerProjection {
    private final Matrix4f inverseProjection;
    private final Matrix4f projection;
    private final double width;
    private final double height;

    public ScreenPointerProjection(Matrix4fc projection, double width, double height) {
        if (!(width > 0) || !(height > 0) || !Double.isFinite(width) || !Double.isFinite(height)
                || !projection.isFinite() || projection.determinant() == 0) {
            throw new IllegalArgumentException("A finite, invertible projection and positive viewport are required");
        }
        inverseProjection = new Matrix4f(projection).invert();
        this.projection = new Matrix4f(projection);
        if (!inverseProjection.isFinite()) throw new IllegalArgumentException("Projection inverse is not finite");
        this.width = width;
        this.height = height;
    }

    /** Projects a view-local point through the same render-time matrix used by pointer rays. */
    public @Nullable Vector2f project(Vector3f point) {
        var clip=projection.transform(new org.joml.Vector4f(point,1));
        if(!clip.isFinite()||clip.w<=0)return null;
        return new org.joml.Vector2f((float)((clip.x/clip.w+1)*width*.5),(float)((1-clip.y/clip.w)*height*.5));
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
