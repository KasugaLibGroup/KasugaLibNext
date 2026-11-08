package test.kasuga.create.bogey;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import lib.kasuga.rendering.models.mc.proxies.Proxies;
import lib.kasuga.rendering.models.mc.proxies.ProxyHost;
import lib.kasuga.rendering.models.mc.proxies.ProxyInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

public final class ExampleBogeyBlockEntity extends AbstractBogeyBlockEntity implements ProxyHost {

    public static final Id MACHINE = Id.fromNamespaceAndPath("kasuga_lib", "bogey_machine");

    /** Degrees per tick the demo spin advances while the block is powered ({@code 6} ≈ 120°/s). */
    private static final float DEMO_SPIN_PER_TICK = 6f;

    private final Supplier<BogeyStyle> defaultStyle;
    private final ProxyInstance<ExampleBogeyBlockEntity> proxy;

    public ExampleBogeyBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            Supplier<BogeyStyle> defaultStyle
    ) {
        super(type, pos, state);
        this.defaultStyle = defaultStyle;
        this.proxy = Proxies.install(this, AllExampleBogey.bogeyProxy());
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
    public ProxyInstance<?> proxy() {
        return this.proxy;
    }

    @Override
    public void setRemoved() {
        this.proxy.release(level);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        this.proxy.release(level);
        super.onChunkUnloaded();
    }
}
