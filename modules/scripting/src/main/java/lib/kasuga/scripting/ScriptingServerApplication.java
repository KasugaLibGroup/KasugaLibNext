package lib.kasuga.scripting;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.core.resource.ServerResourceReloadFinishEvent;
import lib.kasuga.inject.auto_configure.Configurable;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import lib.kasuga.scripting.client.ScriptEngineErrorScreen;
import lib.kasuga.scripting.client.ScriptEngineMissingScreen;
import lib.kasuga.scripting.discovery.PackageSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.struct.InjectorGroupInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Context()
@BeanOnlyIn.DedicatedServer()
public class ScriptingServerApplication implements Configurable {

    Logger logger = LogUtils.getLogger();

    @Inject()
    PackageSystem packageSystem;


    @Inject() @Named("forgeEventBus")
    IEventBus eventBus;

    private ModConfigSpec.BooleanValue IGNORE_ERRORS;

    @Override
    public void configureServer(ModConfigSpec.Builder builder) {
        IGNORE_ERRORS = builder
                .comment("Disable the scripting error")
                .define("ignore_scripting_errors" , false);
    }

    @PostConstruct()
    public void init() {
        eventBus.addListener(this::onResourceReloadFinished);
    }

    private void onResourceReloadFinished(ServerResourceReloadFinishEvent serverResourceReloadFinishEvent) {

        Set<String> missingEngines = packageSystem.getMissingEngines();

        if(!missingEngines.isEmpty()) {
            String missingEnginePrompt = "Missing scripting engine: " + String.join(", ", missingEngines) + ", please install them now!";

            if(IGNORE_ERRORS.getAsBoolean()) {
                logger.warn(missingEnginePrompt);
            } else {
                throw new RuntimeException(missingEnginePrompt);
            }
        }

        if(!packageSystem.getEngineErrors().isEmpty()) {
            for (Map.Entry<String, List<Throwable>> value : packageSystem.getEngineErrors().entrySet()) {
                logger.error(value.toString());
            }
            throw new RuntimeException("Script Engine System Error Detected: " + String.join(", ", packageSystem.getEngineErrors().values().stream().flatMap(Collection::stream).map(Throwable::toString).toList()));
        }
    }
}
