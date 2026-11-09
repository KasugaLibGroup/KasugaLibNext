package lib.kasuga.create.registration.track;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockItem;
import com.simibubi.create.content.trains.track.TrackMaterial;
import lib.kasuga.create.registration.CreateClientBridge;
import lib.kasuga.create.registration.CreateRegistrationStages;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ScopeHelper;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.item.ItemReg;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Objects;
import java.util.function.UnaryOperator;

public final class TrackReg<T extends TrackBlock> extends Reg<TrackReg<T>, T> {
    @FunctionalInterface
    public interface TrackBlockFactory<T extends TrackBlock> {
        T create(BlockBehaviour.Properties properties, TrackMaterial material);
    }

    private final TrackMaterialReg material;
    private final BlockReg<T> blockReg;
    private final ItemReg<TrackBlockItem> itemReg;
    private CreateClientBridge clientBridge = CreateClientBridge.noop();

    public TrackReg(String name, TrackMaterialReg material, TrackBlockFactory<T> factory) {
        this.material = Objects.requireNonNull(material, "material");
        Objects.requireNonNull(factory, "factory");

        this.blockReg = new BlockReg<>(name, ignored -> properties -> {
            return factory.create(properties, material.getEntry());
        });
        this.blockReg.onRegistered(block -> clientBridge.trackBlockCreated(block));
        this.itemReg = new ItemReg<>(name, item -> properties ->
                new TrackBlockItem(item.transform(BlockReg.SCOPE, null), properties));

        configure(ScopeHelper.effect(BlockReg.SCOPE, blockReg::getEntry));
        blockReg.setParent(this);
        itemReg.setParent(this);
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(CreateRegistrationStages.PREPARE, event -> clientBridge = event.clientBridge());
    }

    public TrackReg<T> withBlockProperties(UnaryOperator<BlockBehaviour.Properties> modifier) {
        withProperty(BlockBehaviour.Properties.class, modifier);
        return this;
    }

    public TrackReg<T> withItemProperties(UnaryOperator<Item.Properties> modifier) {
        withProperty(Item.Properties.class, modifier);
        return this;
    }

    public TrackMaterialReg getMaterialReg() {
        return material;
    }

    public BlockReg<T> getBlockReg() {
        return blockReg;
    }

    public ItemReg<TrackBlockItem> getItemReg() {
        return itemReg;
    }

    @Override
    public T getEntry() {
        return blockReg.getEntry();
    }

    public TrackBlockItem getItem() {
        return itemReg.getEntry();
    }
}
