package lib.kasuga.rendering.models.mc.proxies;

import jakarta.annotation.Nullable;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Supplier;

public record ProxyConfig(
        @Nullable Id machine,
        @Nullable ResourceLocation model,
        @Nullable String modelName,
        @Nullable Supplier<Transform> rootTransform
        ) {
        public static ProxyConfig machine(Id id, ResourceLocation model) {
                return new ProxyConfig(id, model, null, null);
        }

        public static ProxyConfig model(ResourceLocation model) {
                return new ProxyConfig(null, model, null, null);
        }
}
