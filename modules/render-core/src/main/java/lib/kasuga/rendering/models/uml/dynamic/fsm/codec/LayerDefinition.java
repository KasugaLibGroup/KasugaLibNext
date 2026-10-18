package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BlendMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BoneMask;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven layer definition: parallel state graph + blend properties.
 */
public record LayerDefinition(
        String id,
        BlendMode mode,
        float weight,
        Optional<String> boneMask,
        List<StateDefinition> states,
        List<TransitionDefinition> transitions,
        String initialState
) {

    public static final Codec<LayerDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(LayerDefinition::id),
            BlendMode.CODEC.optionalFieldOf("mode", BlendMode.BASE).forGetter(LayerDefinition::mode),
            Codec.FLOAT.optionalFieldOf("weight", 1f).forGetter(LayerDefinition::weight),
            Codec.STRING.optionalFieldOf("bone_mask").forGetter(LayerDefinition::boneMask),
            StateDefinition.CODEC.listOf().optionalFieldOf("states", List.of()).forGetter(LayerDefinition::states),
            TransitionDefinition.CODEC.listOf().optionalFieldOf("transitions", List.of()).forGetter(LayerDefinition::transitions),
            Codec.STRING.fieldOf("initial_state").forGetter(LayerDefinition::initialState)
    ).apply(instance, LayerDefinition::new));

    public BoneMask resolvedMask() {
        return boneMask.map(BoneMask::of).orElseGet(BoneMask::all);
    }
}
