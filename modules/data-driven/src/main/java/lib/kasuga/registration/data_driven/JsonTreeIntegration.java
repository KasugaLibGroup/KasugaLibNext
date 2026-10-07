package lib.kasuga.registration.data_driven;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.registration.core.RegisterContextRegistry;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import org.slf4j.Logger;

@Context
public class JsonTreeIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject
    private RegisterContextRegistry registry;

    @PostConstruct
    public void init() {
        // Build the per-mod JSON tree lazily on first registry dispatch, after all
        // @Context static initializers have registered their factory types.
        registry.register(RegisterContextRegistry.Side.COMMON, (modRegistry, eventBus) -> {
            try {
                JsonRegistryGroup jsonRoot = JsonTreeBuilder.buildForMod(modRegistry.getModId());
                if (jsonRoot != null) {
                    modRegistry.addChild(jsonRoot);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load JSON registrations for mod '{}'", modRegistry.getModId(), e);
            }
        });
    }
}
