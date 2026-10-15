package lib.kasuga.rendering.models.mc.typo.mtb;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector3f;

public class MtbBox extends MtbPolygon {

    public MtbBox(String name, @Nullable String group, Vector3f size, Vector3f offset, Vector3f position, Vector3f rotation, Vector2i uv) {
        super(name, group, size, offset, position, rotation, uv);
    }
}
