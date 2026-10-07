package test.kasuga.data_driven;

import io.micronaut.context.annotation.Context;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.registration.minecraft.item.ItemReg;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import test.kasuga.data_driven.block.BEBlock;
import test.kasuga.data_driven.block.OccludingTestBlock;
import test.kasuga.data_driven.block.SimpleTestBlock;
import test.kasuga.data_driven.block_entity.TestBlockEntity;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

@Context
public class DataDrivenTestFactories {

    @SuppressWarnings("unchecked")
    private static <T extends Block> Reg<?, Block> blockWithItem(
            String id, Function<BlockBehaviour.Properties, T> blockSupplier) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
    }

    static {
        // Block factories — each factory is responsible for creating its own BlockItem
        FactoryRegistry.register("simple_block", (id, params) -> blockWithItem(id, SimpleTestBlock::new));
        FactoryRegistry.register("be_block", (id, params) -> blockWithItem(id, BEBlock::new));
        FactoryRegistry.register("occluding_block", (id, params) -> blockWithItem(id, OccludingTestBlock::new));

        // Factory that exercises params: reads "occluding" boolean
        FactoryRegistry.register("params_block", (id, params) -> {
            if (params != null && params.has("occluding") && params.get("occluding").getAsBoolean()) {
                return blockWithItem(id, OccludingTestBlock::new);
            }
            return blockWithItem(id, SimpleTestBlock::new);
        });

        // Item factories
        FactoryRegistry.registerItem("simple_item", (id, params) -> ItemReg.of(id, Item::new));

        // Block entity factories
        FactoryRegistry.registerBlockEntity("test_be", (id, validBlocks, params) -> {
            BlockEntityReg<TestBlockEntity> reg = new BlockEntityReg<>(id,
                    r -> (pos, state) -> new TestBlockEntity(r.getEntry(), pos, state));
            reg.withProperty(Collection.class,
                col -> { col.addAll(Arrays.asList(validBlocks.get())); return col; });
            return reg;
        });
    }
}
