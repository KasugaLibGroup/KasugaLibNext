package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * Data-driven declaration of a {@link lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar}. The
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory} resolves each entry to a
 * registered or anonymously-registered var.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>reference</b> ({@code "ns:path"}): reuse an already-registered var; {@code type}/{@code default}
 *       are ignored.</li>
 *   <li><b>inline</b> (default): declare a new var — {@code type} selects a built-in codec token
 *       (bool/int/float/string/resource/vec3) and {@code default} is decoded against it.</li>
 * </ul>
*  {@code ephemeral: true} marks a tick-scoped trigger var (cleared at the end of every tick).
 *  {@code external_writable: false} marks a derived parameter (machine-internal writers only) and
 *  {@code sync: true} marks a parameter pushed over the FSM sync channel (server-authoritative).
 */
public record StateVarDefinition(
        String name,
        String type,
        Optional<JsonElement> defaultValue,
        Optional<String> reference,
        boolean ephemeral,
        boolean externalWritable,
        boolean sync
) {

    /** A passthrough codec that keeps a raw {@link JsonElement} so the factory can decode it per-type. */
    public static final Codec<JsonElement> JSON_ELEMENT = Codec.PASSTHROUGH.comapFlatMap(
            dynamic -> {
                Object value = dynamic.getValue();
                if (value instanceof JsonElement element) {
                    return DataResult.success(element);
                }
                return DataResult.error(() -> "expected a JSON element, got "
                        + (value == null ? "null" : value.getClass()));
            },
            element -> new Dynamic<>(JsonOps.INSTANCE, element)
    );

    public static final Codec<StateVarDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(StateVarDefinition::name),
            Codec.STRING.optionalFieldOf("type", "float").forGetter(StateVarDefinition::type),
            JSON_ELEMENT.optionalFieldOf("default").forGetter(StateVarDefinition::defaultValue),
            Codec.STRING.optionalFieldOf("reference").forGetter(StateVarDefinition::reference),
            Codec.BOOL.optionalFieldOf("ephemeral", false).forGetter(StateVarDefinition::ephemeral),
            Codec.BOOL.optionalFieldOf("external_writable", true).forGetter(StateVarDefinition::externalWritable),
            Codec.BOOL.optionalFieldOf("sync", false).forGetter(StateVarDefinition::sync)
    ).apply(instance, StateVarDefinition::new));
}
