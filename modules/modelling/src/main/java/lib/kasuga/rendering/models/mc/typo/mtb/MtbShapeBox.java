package lib.kasuga.rendering.models.mc.typo.mtb;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector3f;

public class MtbShapeBox extends MtbPolygon {

    public final Vector3f[] corners;

    public MtbShapeBox(String name, @Nullable String group,
                       Vector3f size, Vector3f offset, Vector3f position, Vector3f rotation,
                       Vector2i uv, Vector3f[] corners) {
        super(name, group, size, offset, position, rotation, uv);
        this.corners = corners;
        if (corners.length != 8) {
            throw new IllegalArgumentException("A ShapeBox must have exactly 8 corners.");
        }
    }
}
