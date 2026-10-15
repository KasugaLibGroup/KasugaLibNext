package lib.kasuga.rendering.models.mc.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Multiplexer;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.VariantFactory;

import java.util.function.Consumer;

/**
 * Minecraft-specific convenience factory for {@link Multiplexer}s that use {@link McContext} and
 * {@link McVariant}. Hides the generic machinery from ordinary mod code.
 */
public final class McMultiplexer {

    private static final VariantFactory<McVariant> FACTORY = McVariant::new;

    private McMultiplexer() {}

    public static Multiplexer<McContext, McVariant> define(Consumer<Multiplexer.Builder<McContext, McVariant>> config) {
        return Multiplexer.define(FACTORY, config);
    }
}
