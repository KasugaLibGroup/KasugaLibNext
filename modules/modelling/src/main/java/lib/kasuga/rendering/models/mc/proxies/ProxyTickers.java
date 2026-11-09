package lib.kasuga.rendering.models.mc.proxies;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Ready-made {@link BlockEntityTicker}s that drive a {@link ProxySessionHost} from the vanilla
 * block entity tick channel, with a fallback for the legacy {@link ProxyHost} bridge.
 *
 * <p>{@code BlockState#getTicker} is evaluated when the block entity is registered and again
 * whenever its block state is replaced; the returned ticker is then called once per game tick by
 * {@code Level#tickBlockEntities}. These factories keep the per-type knowledge out of content code:
 * the ticker is type-agnostic and consults the registration-time catalog in
 * {@link BlockEntityProxyDefinitions}; it no-ops when the block entity implements neither host
 * interface or its type has no registered definition.
 *
 * <h2>Replacing vs. composing</h2>
 * <p>{@code EntityBlock#getTicker} returns {@code null} by default, but many blocks (vanilla
 * furnaces, hoppers, spawners, beehives, beacons, campfires, and plenty of modded blocks) return a
 * ticker of their own. Returning one of these factories directly would drop that logic. Use the
 * two-argument overload and pass the previous ticker in:
 *
 * <pre>{@code
 * @Override
 * public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
 *                                                               BlockEntityType<T> type) {
 *     return ProxyTickers.forBlockEntity(type, super.getTicker(level, state, type));
 * }
 * }</pre>
 *
 * <p>{@link BlockEntityTicker} has no {@code andThen} in this version, so composition has to be a
 * lambda; the proxy tick always runs after the wrapped one.
 *
 * <h2>Statelessness</h2>
 * <p>The returned lambdas capture only the immutable type token and the wrapped ticker. The ticker
 * object is rebuilt on every block state change, while the block entity instance is not.
 *
 * @see ProxySessionHost
 * @see ProxyInstance#tick(Level, net.minecraft.core.BlockPos)
 */
public final class ProxyTickers {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ProxyTickers() {
    }

    /**
     * Drives the proxy on both sides. Equivalent to
     * {@code forBlockEntity(type, null)}.
     *
     * @param type the block entity type being ticked; selects its registered definition
     */
    public static <T extends BlockEntity> BlockEntityTicker<T> forBlockEntity(BlockEntityType<T> type) {
        return forBlockEntity(type, null);
    }

    /**
     * Drives the proxy on both sides, after the given ticker.
     *
     * @param type     the block entity type being ticked; selects its registered definition
     * @param existing the block's previous ticker, or {@code null} if it had none. It runs first so
     *                 that a legacy {@link ElementProxy#pull} observes this tick's block state
     */
    public static <T extends BlockEntity> BlockEntityTicker<T> forBlockEntity(
            BlockEntityType<T> type, @Nullable BlockEntityTicker<T> existing) {
        return (level, pos, state, blockEntity) -> {
            if (existing != null) {
                existing.tick(level, pos, state, blockEntity);
            }
            if (blockEntity instanceof ProxySessionHost host) {
                tickKernel(level, type, blockEntity, host);
            } else if (blockEntity instanceof ProxyHost host) {
                host.proxy().tick(level, pos);
            }
        };
    }

    /**
     * Drives the proxy on the client only. Use for content whose proxy is purely visual (a model
     * with no state machine): the server then never attaches a kernel session.
     *
     * <p>A block that needs server-side machine logic must use {@link #forBlockEntity} instead.
     */
    public static <T extends BlockEntity> BlockEntityTicker<T> forClientBlockEntity(BlockEntityType<T> type) {
        return (level, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel) {
                return;
            }
            if (blockEntity instanceof ProxySessionHost host) {
                tickKernel(level, type, blockEntity, host);
            } else if (blockEntity instanceof ProxyHost host) {
                host.proxy().tick(level, pos);
            }
        };
    }

    private static <T extends BlockEntity> void tickKernel(
            Level level,
            BlockEntityType<T> type,
            T blockEntity,
            ProxySessionHost host
    ) {
        tickHost(type, blockEntity, host, level.getGameTime(), 1.0 / 20.0);
    }

    static <T> boolean tickHost(
            Object type,
            T blockEntity,
            ProxySessionHost host,
            long gameTime,
            double deltaSeconds
    ) {
        ProxySession<?, ?, ?, ?, ?> session = host.proxySession();
        if (session != null && session.isClosed()) {
            host.proxySession(null);
            session = null;
        }
        if (session == null) {
            ProxyAttachToken token = host.proxyAttachToken();
            try {
                session = BlockEntityProxyDefinitions.attachForType(
                        type, blockEntity, token).orElse(null);
            } catch (RuntimeException failure) {
                LOGGER.error("[proxy] failed to attach kernel session for {} ({})",
                        type, token, failure);
                return false;
            }
            if (session == null) {
                return false;
            }
            host.proxySession(session);
        }
        session.tick(gameTime, deltaSeconds);
        return true;
    }
}
