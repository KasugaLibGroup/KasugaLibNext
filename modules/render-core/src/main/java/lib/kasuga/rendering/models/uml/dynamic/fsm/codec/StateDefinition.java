package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven state node. Enter/exit/update actions and the pose target are resolved
 * by {@link Id} references; an optional {@code clip} references a registered animation clip.
 */
public record StateDefinition(
        String id,
        Optional<Integer> durationTicks,
        PoseDefinition pose,
        List<Id> onEnter,
        List<Id> onExit,
        List<Id> onUpdate,
        Optional<ClipDefinition> clip
) {

    public static final Codec<StateDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(StateDefinition::id),
            Codec.INT.optionalFieldOf("duration_ticks").forGetter(StateDefinition::durationTicks),
            PoseDefinition.CODEC.optionalFieldOf("pose", PoseDefinition.EMPTY).forGetter(StateDefinition::pose),
            Id.CODEC.listOf().optionalFieldOf("on_enter", List.of()).forGetter(StateDefinition::onEnter),
            Id.CODEC.listOf().optionalFieldOf("on_exit", List.of()).forGetter(StateDefinition::onExit),
            Id.CODEC.listOf().optionalFieldOf("on_update", List.of()).forGetter(StateDefinition::onUpdate),
            ClipDefinition.CODEC.optionalFieldOf("clip").forGetter(StateDefinition::clip)
    ).apply(instance, StateDefinition::new));

    /**
     * An animation clip reference resolved against the FSM clip registry: a clip {@link Id} plus whether it
     * loops. Decodes from either {@code { "id": "...", "loop": true }} or a plain string {@code "kasuga_lib:fan_on"}
     * (loop defaults to false); encodes the object form only when the clip loops.
     */
    public record ClipDefinition(Id id, boolean loop) {

        private static final Codec<ClipDefinition> OBJECT = RecordCodecBuilder.create(instance -> instance.group(
                Id.CODEC.fieldOf("id").forGetter(ClipDefinition::id),
                Codec.BOOL.optionalFieldOf("loop", false).forGetter(ClipDefinition::loop)
        ).apply(instance, ClipDefinition::new));

        public static final Codec<ClipDefinition> CODEC = Codec.either(OBJECT, Id.CODEC).xmap(
                either -> either.map(left -> left, id -> new ClipDefinition(id, false)),
                def -> def.loop() ? Either.left(def) : Either.right(def.id())
        );
    }
}
