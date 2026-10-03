package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven transform target for a bone. Angles are in degrees.
 */
public record TransformDefinition(
        Vector3f translate,
        Vector3f rotate,
        Vector3f scale
) {

    public static final Codec<Vector3f> VEC3F_CODEC = Codec.FLOAT.listOf(3, 3).xmap(
            list -> new Vector3f(list.get(0), list.get(1), list.get(2)),
            vec -> List.of(vec.x, vec.y, vec.z)
    );

    public static final TransformDefinition IDENTITY = new TransformDefinition(new Vector3f(), new Vector3f(), new Vector3f(1f, 1f, 1f));

    public static final Codec<TransformDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VEC3F_CODEC.optionalFieldOf("translate", new Vector3f()).forGetter(TransformDefinition::translate),
            VEC3F_CODEC.optionalFieldOf("rotate", new Vector3f()).forGetter(TransformDefinition::rotate),
            VEC3F_CODEC.optionalFieldOf("scale", new Vector3f(1f, 1f, 1f)).forGetter(TransformDefinition::scale)
    ).apply(instance, TransformDefinition::new));

    public TransformDefinition(Optional<Vector3f> translate, Optional<Vector3f> rotate, Optional<Vector3f> scale) {
        this(translate.orElse(new Vector3f()), rotate.orElse(new Vector3f()), scale.orElse(new Vector3f(1f, 1f, 1f)));
    }
}
