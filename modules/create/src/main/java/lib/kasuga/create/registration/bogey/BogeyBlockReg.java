package lib.kasuga.create.registration.bogey;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import lib.kasuga.create.registration.CreateRegistrationEvent;
import lib.kasuga.create.registration.CreateRegistrationStages;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.minecraft.block.BlockReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Objects;
import java.util.function.UnaryOperator;

public final class BogeyBlockReg<T extends AbstractBogeyBlock<?>> extends Reg<BogeyBlockReg<T>, T> {
    @FunctionalInterface
    public interface BogeyBlockFactory<T extends AbstractBogeyBlock<?>> {
        T create(BlockBehaviour.Properties properties, BogeySizes.BogeySize size);
    }

    private final BogeySizeReg size;
    private final BlockReg<T> blockReg;

    public BogeyBlockReg(String name, BogeySizeReg size, BogeyBlockFactory<T> factory) {
        this.size = Objects.requireNonNull(size, "size");
        Objects.requireNonNull(factory, "factory");
        this.blockReg = new BlockReg<>(name, ignored -> properties -> factory.create(properties, size.getEntry()));
        this.blockReg.setParent(this);
    }

    public BogeyBlockReg<T> withBlockProperties(UnaryOperator<BlockBehaviour.Properties> modifier) {
        withProperty(BlockBehaviour.Properties.class, modifier);
        return this;
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(CreateRegistrationStages.PREPARE, event -> {
            ResourceLocation id = blockReg.resolveId();
            event.submit(
                    CreateRegistrationEvent.Phase.BOGEY_BLOCK,
                    id,
                    this,
                    () -> validate(event, id),
                    () -> { }
            );
        });
    }

    private void validate(CreateRegistrationEvent event, ResourceLocation id) {
        if (!size.isCommitted()
                && !event.contains(CreateRegistrationEvent.Phase.BOGEY_SIZE, size.resolveId(), size)) {
            throw new IllegalStateException("Bogey block " + id + " refers to an unregistered size");
        }
    }

    public BogeySizeReg getSizeReg() {
        return size;
    }

    public BlockReg<T> getBlockReg() {
        return blockReg;
    }

    ResourceLocation resolveId() {
        return blockReg.resolveId();
    }

    boolean isRegistered() {
        return blockReg.getResourceLocation() != null;
    }

    @Override
    public T getEntry() {
        return blockReg.getEntry();
    }
}
