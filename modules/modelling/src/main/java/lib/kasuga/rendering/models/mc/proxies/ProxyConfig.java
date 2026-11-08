package lib.kasuga.rendering.models.mc.proxies;

import jakarta.annotation.Nullable;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Supplier;

/**
 * What a {@link ProxyInstance} attaches to one element.
 *
 * <p>Three mutually exclusive shapes, in increasing expressiveness:
 * <ul>
 *     <li><b>machine only</b> — logic on both sides, nothing drawn (<code>model</code> and
 *     <code>parts</code> empty);</li>
 *     <li><b>single model</b> — <code>model</code> set, <code>parts</code> empty; the model is bound
 *     as one {@code ModelInstance} under the proxy's identity key, and a machine (when present)
 *     drives its skeleton through the pose driver;</li>
 *     <li><b>multi-part</b> — <code>parts</code> non-empty; every part is bound as its own
 *     {@code ModelInstance} under <code>&lt;identity&gt;/part/&lt;i&gt;</code>. A machine, when
 *     present, is then <em>only</em> a parameter source: it is built without a model, so no
 *     skeleton is posed by it and no part can be mounted twice.</li>
 * </ul>
 *
 * <p>{@code model} and {@code parts} are therefore mutually exclusive — passing both throws, because
 * the same geometry would be bound under two different instance keys (two GPU instances, two
 * draws). {@link ModelPart#spin()} on the multi-part path is the supported way to make a machine
 * animate geometry.
 *
 * @param machine       state machine definition, or {@code null} for a static proxy
 * @param model         single-model binding, or {@code null} when using {@code parts}
 * @param modelName     MMD model name for the single-model binding, otherwise {@code null}
 * @param rootTransform the proxy root pose; {@code null} means "a fresh position-based transform
 *                      per read" (block hosts default to the block centre)
 * @param parts         multi-part binding, empty for the single-model and machine-only shapes
 */
public record ProxyConfig(
        @Nullable Id machine,
        @Nullable ResourceLocation model,
        @Nullable String modelName,
        @Nullable Supplier<Transform> rootTransform,
        List<ModelPart> parts
) {

    public ProxyConfig {
        parts = parts == null || parts.isEmpty() ? List.of() : List.copyOf(parts);
        if (model != null && !parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "ProxyConfig carries both a single model (" + model + ") and " + parts.size()
                            + " part(s); list the model as a ModelPart instead — otherwise it would be "
                            + "bound under two instance keys and drawn twice");
        }
    }

    /** Legacy single-shape config: a machine and/or a single model, no parts. */
    public ProxyConfig(@Nullable Id machine, @Nullable ResourceLocation model, @Nullable String modelName,
                       @Nullable Supplier<Transform> rootTransform) {
        this(machine, model, modelName, rootTransform, List.of());
    }

    public static ProxyConfig machine(Id id, ResourceLocation model) {
        return new ProxyConfig(id, model, null, null);
    }

    public static ProxyConfig model(ResourceLocation model) {
        return new ProxyConfig(null, model, null, null);
    }

    /** Model-only multi-part proxy: no machine, every part static. */
    public static ProxyConfig parts(List<ModelPart> parts) {
        return new ProxyConfig(null, null, null, null, parts);
    }

    /** Multi-part proxy whose parts may read machine parameters (e.g. spin angles). */
    public static ProxyConfig parts(Id machine, List<ModelPart> parts) {
        return new ProxyConfig(machine, null, null, null, parts);
    }

    /** Replaces the root transform, keeping everything else. */
    public ProxyConfig withRoot(Supplier<Transform> rootTransform) {
        return new ProxyConfig(machine, model, modelName, rootTransform, parts);
    }

    /** Whether this config asks for nothing at all — the element should be refused. */
    public boolean isEmpty() {
        return machine == null && model == null && parts.isEmpty();
    }
}
