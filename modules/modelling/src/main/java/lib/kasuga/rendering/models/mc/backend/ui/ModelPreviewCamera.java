package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Origin-local orthographic framing, shared by every UI toolkit. */
public final class ModelPreviewCamera {
    private ModelPreviewCamera() {}

    public record Frame(Matrix4f modelView, Matrix4f projection) {}

    public static Frame frame(Vector3fc min, Vector3fc max, float aspect, float yaw, float pitch, float zoom) {
        if (!(aspect > 0) || !(zoom > 0) || !Float.isFinite(aspect) || !Float.isFinite(zoom)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !min.isFinite() || !max.isFinite()
                || min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Preview bounds, aspect, rotation and zoom must be finite and valid");
        }
        var center = new Vector3f(min).add(max).mul(.5f);
        float radius = Math.max(.001f, new Vector3f(max).sub(min).length() * .5f);
        float halfHeight = radius * 1.1f * Math.max(1, 1 / aspect) / zoom;
        var view = new Matrix4f().rotateX(pitch).rotateY(yaw).translate(-center.x, -center.y, -center.z);
        var projection = new Matrix4f().setOrtho(-halfHeight * aspect, halfHeight * aspect,
                -halfHeight, halfHeight, -radius * 4, radius * 4);
        if (!view.isFinite() || !projection.isFinite()) throw new IllegalArgumentException("Preview camera exceeds finite matrix range");
        return new Frame(view, projection);
    }
}
