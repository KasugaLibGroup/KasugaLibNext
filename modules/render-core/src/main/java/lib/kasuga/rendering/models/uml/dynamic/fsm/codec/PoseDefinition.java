package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;

/**
 * Data-driven pose target: inline morph weights, bone transforms and material frames.
 */
public record PoseDefinition(
        Map<String, Float> morphs,
        List<BoneDefinition> bones,
        List<FrameDefinition> frames
) {

    public static final PoseDefinition EMPTY = new PoseDefinition(Map.of(), List.of(), List.of());

    public static final Codec<PoseDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("morphs", Map.of()).forGetter(PoseDefinition::morphs),
            BoneDefinition.CODEC.listOf().optionalFieldOf("bones", List.of()).forGetter(PoseDefinition::bones),
            FrameDefinition.CODEC.listOf().optionalFieldOf("frames", List.of()).forGetter(PoseDefinition::frames)
    ).apply(instance, PoseDefinition::new));

    public record BoneDefinition(
            String name,
            TransformDefinition transform,
            String mode
    ) {
        public static final Codec<BoneDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(BoneDefinition::name),
                TransformDefinition.CODEC.fieldOf("transform").forGetter(BoneDefinition::transform),
                Codec.STRING.optionalFieldOf("mode", "replace").forGetter(BoneDefinition::mode)
        ).apply(instance, BoneDefinition::new));
    }

    public record FrameDefinition(
            String material,
            int frame
    ) {
        public static final Codec<FrameDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("material").forGetter(FrameDefinition::material),
                Codec.INT.fieldOf("frame").forGetter(FrameDefinition::frame)
        ).apply(instance, FrameDefinition::new));
    }
}
