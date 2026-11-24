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

    private ModConfigSpec.BooleanValue contraptionLod, multiBogeyCarriage, carriageYOffset, derailedOnBogeyOffsetOverflow;

    private ModConfigSpec.DoubleValue maxBogeyOffset, maxCarriageYOffset;

    /** Whether carriages may capture and use more than the vanilla two bogeys. */
    public static boolean isMultiBogeyCarriageEnabled() {
        return INSTANCE.get().multiBogeyCarriage.getAsBoolean();
    }

    public static boolean isCarriageYOffestEnabled() {
        return INSTANCE.get().carriageYOffset.getAsBoolean();
    }

    public static double maxBogeyOffset() {
        return INSTANCE.get().maxBogeyOffset.getAsDouble();
    }

    public static double maxCarriageYOffset() {
        return INSTANCE.get().maxCarriageYOffset.getAsDouble();
    }

    public static boolean derailedOnBogeyOffsetEnabled() {
        return INSTANCE.get().derailedOnBogeyOffsetOverflow.getAsBoolean();
    }

    public static boolean isContraptionLodEnabled() {
        return INSTANCE.get().contraptionLod.getAsBoolean();
    }

    @Override
    public void configureCommon(ModConfigSpec.Builder builder) {
        multiBogeyCarriage = builder
                .comment("Allow carriages to be assembled with more than two bogeys.",
                        "When false, assembly, persistence, sync and rendering use the vanilla two-bogey path.")
                .translation("kasuga_lib.configuration.enable_multi_bogey_carriage")
                .define("enable_multi_bogey_carriage", KasugaLibCreateApi::getMultiBogeyCarriageDefault);
        carriageYOffset = builder
                .comment("Allow carriages to have y offsets.")
                .translation("kasuga_lib.configuration.enable_carriage_yoffset")
                .define("enable_carriage_y_offset", KasugaLibCreateApi::getCarriageYOffsetDefault);
        maxBogeyOffset = builder
                .comment("Maximum lateral distance (in blocks) an interior bogey may be shifted out of",
                        "its carriage-local block position while the train is passing curves.",
                        "The curve offset of interior bogeys is clamped to this magnitude.")
                .translation("kasuga_lib.configuration.max_bogey_offset")
                .defineInRange("max_bogey_offset", 1.0D, 0.0D, Double.MAX_VALUE);
        maxCarriageYOffset = builder
                .comment("Maximum vertical distance (in blocks) the carriage body may be offset from",
                        "the bogeys, in either direction.",
                        "The carriage y offset is clamped to this magnitude.")
                .translation("kasuga_lib.configuration.max_carriage_y_offset")
                .defineInRange("max_carriage_y_offset", 0.5D, 0.0D, Double.MAX_VALUE);
        derailedOnBogeyOffsetOverflow = builder
                .comment("If the train will be derailed while bogeys reach their max offsets.")
                .translation("kasuga_lib.configuration.derailed_on_bogey_offset_overflow")
                .define("derailed_on_bogey_offset_overflow", KasugaLibCreateApi::getDerailedOnBogeyOffsetDefault);
        contraptionLod = builder.comment("Send distant render meshes for active Create contraptions.")
                .define("enable_contraption_lod", true);
    }
}
