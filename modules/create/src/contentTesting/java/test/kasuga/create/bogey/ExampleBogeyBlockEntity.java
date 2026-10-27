package test.kasuga.create.bogey;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import lib.kasuga.create.proxy.BogeyProxy;
import lib.kasuga.rendering.models.mc.proxies.Proxies;
import lib.kasuga.rendering.models.mc.proxies.ProxyHost;
import lib.kasuga.rendering.models.mc.proxies.ProxyInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

public final class ExampleBogeyBlockEntity extends AbstractBogeyBlockEntity implements ProxyHost {

    public static final Id MACHINE = Id.fromNamespaceAndPath("kasuga_lib", "bogey_machine");

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
        this.proxy = Proxies.install(this, new BogeyProxy<>(null, null));
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
