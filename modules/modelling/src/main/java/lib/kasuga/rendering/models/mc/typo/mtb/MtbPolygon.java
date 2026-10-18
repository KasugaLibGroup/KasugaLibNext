package lib.kasuga.rendering.models.mc.typo.mtb;

import lombok.AllArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;

@AllArgsConstructor
public class MtbPolygon {

    public final String name;

    @Nullable
    public final String group;
    public final Vector3f size, offset, position, rotation;
    public final Vector2i uv;
}
