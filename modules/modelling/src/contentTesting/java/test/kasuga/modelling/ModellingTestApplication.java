package test.kasuga.modelling;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.Registry;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;

@Context()
public class ModellingTestApplication {
    public static Registry registry = KasugaLibApplication.REGISTRY;
    private final Logger logger = LogUtils.getLogger();

    @Inject() @Named("modEventBus") IEventBus eventBus;

    @PostConstruct()
    public void init() {
        logger.info("ModellingTestApplication initialized, reusing KasugaLibApplication.REGISTRY");
    }
}
