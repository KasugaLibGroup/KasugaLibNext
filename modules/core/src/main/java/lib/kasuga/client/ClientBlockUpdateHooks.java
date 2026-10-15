package lib.kasuga.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client-side notification hub fired from {@code ClientLevel#sendBlockUpdated},
 * which covers every world-visible single block change (packets, local edits
 * and fluid/block ticks). Higher modules subscribe here instead of adding
 * their own mixins.
 */
public final class ClientBlockUpdateHooks {
    private static final List<Consumer<Update>> LISTENERS = new CopyOnWriteArrayList<>();

    private ClientBlockUpdateHooks() {}

    /**
     * Registers a listener and returns its removal handle. Listeners must be
     * cheap: this runs for every changed block on the main client thread.
     */
    public static AutoCloseable addListener(Consumer<Update> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    @ApiStatus.Internal
    public static void dispatch(ClientLevel level, long packedPos,
                                BlockState oldState, BlockState newState) {
        if (LISTENERS.isEmpty()) return;
        Update update = new Update(level, packedPos, oldState, newState);
        for (Consumer<Update> listener : LISTENERS) listener.accept(update);
    }

    /** One client-visible block change. */
    public record Update(ClientLevel level, long packedPos,
                         BlockState oldState, BlockState newState) {}
}
