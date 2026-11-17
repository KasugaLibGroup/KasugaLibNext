package lib.kasuga.create;

import io.micronaut.context.annotation.Context;
import lib.kasuga.KasugaLib;
import lib.kasuga.inject.auto_configure.Configurable;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.util.Lazy;

/**
 * Common config for the Create integration. Mixins read the flags through the static accessors, so
 * the values follow the config on reload.
 */
@Context
@CreateModBeans
public final class CreateConfig implements Configurable {

    private static final Lazy<CreateConfig> INSTANCE = Lazy.of(() -> KasugaLib.getBean(CreateConfig.class));

    private ModConfigSpec.BooleanValue multiBogeyCarriage;

    /** Whether carriages may capture and use more than the vanilla two bogeys. */
    public static boolean isMultiBogeyCarriageEnabled() {
        return INSTANCE.get().multiBogeyCarriage.getAsBoolean();
    }

    @Override
    public void configureCommon(ModConfigSpec.Builder builder) {
        multiBogeyCarriage = builder
                .comment("Allow carriages to be assembled with more than two bogeys.",
                        "When false, assembly, persistence, sync and rendering use the vanilla two-bogey path.")
                .translation("kasuga_lib.configuration.enable_multi_bogey_carriage")
                .define("enable_multi_bogey_carriage", KasugaLibCreateApi::getMultiBogeyCarriageDefault);
    }
}
