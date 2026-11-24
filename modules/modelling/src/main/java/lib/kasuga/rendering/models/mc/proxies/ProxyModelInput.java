package lib.kasuga.rendering.models.mc.proxies;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Immutable block-state observation passed to a proxy-model controller. */
public record ProxyModelInput(BlockState state) {

    public ProxyModelInput {
        Objects.requireNonNull(state, "state");
    }
}
