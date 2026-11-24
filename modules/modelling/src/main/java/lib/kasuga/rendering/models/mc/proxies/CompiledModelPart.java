package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** One resolved model part in a compiled model group. */
public record CompiledModelPart(
        String id,
        ResourceLocation model,
        @Nullable String modelName,
        Transform local
) {

    public CompiledModelPart {
        if (id == null || !id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid model part id: " + id);
        }
        Objects.requireNonNull(model, "model");
        local = Objects.requireNonNull(local, "local").copy();
    }

    @Override
    public Transform local() {
        return local.copy();
    }

    public ModelPart toModelPart() {
        return new ModelPart(model, modelName, local, null);
    }

    public ModelPart toModelPart(ModelPart.Motion motion) {
        return new ModelPart(model, modelName, local, Objects.requireNonNull(motion, "motion"));
    }
}
