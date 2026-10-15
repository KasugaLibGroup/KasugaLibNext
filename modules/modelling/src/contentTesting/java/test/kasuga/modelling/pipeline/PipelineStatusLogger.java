package test.kasuga.modelling.pipeline;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.slf4j.Logger;

@Context()
public class PipelineStatusLogger {
    private final Logger logger = LogUtils.getLogger();

    @Inject() @Named("modEventBus") IEventBus eventBus;

    @PostConstruct()
    public void init() {
        eventBus.addListener(RegisterClientReloadListenersEvent.class, this::onReloadListenerRegister);
    }

    private void onReloadListenerRegister(RegisterClientReloadListenersEvent event) {
        logger.info("=== Modelling Pipeline Status ===");
        logPipeline("BE", PipelineRegistry.be());
        logPipeline("JE", PipelineRegistry.je());
        logPipeline("OBJ", PipelineRegistry.obj());
        logPipeline("PMX", PipelineRegistry.pmx());
        logBackend("MC_BACKEND", PipelineRegistry.backend());
        logger.info("================================");
    }

    private void logPipeline(String name, Object pipeline) {
        logger.info("  {}: {}", name, pipeline != null ? "✓ present" : "✗ null");
    }

    private void logBackend(String name, Object backend) {
        logger.info("  {}: {}", name, backend != null ? "✓ present" : "✗ null");
    }
}
