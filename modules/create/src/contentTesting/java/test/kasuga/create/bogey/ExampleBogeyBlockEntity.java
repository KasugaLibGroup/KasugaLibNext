package test.kasuga.create.bogey;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import lib.kasuga.rendering.models.mc.proxies.ProxySessionHost;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class ExampleBogeyBlockEntity extends AbstractBogeyBlockEntity
        implements ProxySessionHost {

    /** Degrees per tick the demo spin advances while the block is powered ({@code 6} ≈ 120°/s). */
    private static final float DEMO_SPIN_PER_TICK = 6f;

    private final Supplier<BogeyStyle> defaultStyle;
    private final ProxyAttachToken attachToken = ProxyAttachToken.random();
    @Nullable private ProxySession<?, ?, ?, ?, ?> proxySession;

    public ExampleBogeyBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            Supplier<BogeyStyle> defaultStyle
    ) {
        super(type, pos, state);
        this.defaultStyle = defaultStyle;
    }

    /** Client-only demo angle in degrees; real trains leave this at 0 and drive {@code getVirtualAngle}. */
    private float demoAngle;

    /**
     * Advances the demo spin so the wheels turn on a placed (non-train) bogey — a powered block is
     * the only thing that moves them outside a train.
     *
     * <p>Fixture behaviour, not library behaviour: Create's own {@code virtualAnimation} field is
     * package-private, so the demo keeps its own angle instead of faking train movement.
     */
    public void demoSpin(Level level, BlockPos pos) {
        if (level == null || !level.isClientSide() || !level.hasNeighborSignal(pos)) {
            return;
        }
        demoAngle = (demoAngle + DEMO_SPIN_PER_TICK) % 360f;
    }

    /**
     * Latest angle for this game tick. The placed-bogey path asks Create for the current endpoint
     * and lets the tick-domain sampler interpolate between consecutive endpoints exactly once.
     */
    public float wheelAngle() {
        return getVirtualAngle(1f) + demoAngle;
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return defaultStyle.get();
    }

    @Override
    public ProxyAttachToken proxyAttachToken() {
        return attachToken;
    }

    @Override
    public @Nullable ProxySession<?, ?, ?, ?, ?> proxySession() {
        return proxySession;
    }

    @Override
    public void proxySession(@Nullable ProxySession<?, ?, ?, ?, ?> session) {
        if (session != null && proxySession != null && proxySession != session) {
            throw new IllegalStateException("bogey already has a proxy session");
        }
        proxySession = session;
    }

    //region lifecycle

    @Override
    public void setRemoved() {
        closeProxySession();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        closeProxySession();
        super.onChunkUnloaded();
    }

    //endregion
}
