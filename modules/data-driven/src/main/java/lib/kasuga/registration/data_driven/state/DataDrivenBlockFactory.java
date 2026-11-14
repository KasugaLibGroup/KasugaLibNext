package lib.kasuga.registration.data_driven.state;

import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import net.minecraft.world.level.block.Block;

/** Registration of the plain block factory shipped with data-driven. */
public final class DataDrivenBlockFactory {
    public static final String TYPE = "data_driven_block";

    private DataDrivenBlockFactory() {}

    public static void registerIfAbsent() {
        if (!FactoryRegistry.contains(TYPE)) {
            FactoryRegistry.register(
                    TYPE,
                    (StatefulBlockFactory) (id, params, properties) -> create(id, properties),
                    true
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Reg<?, Block> create(String id, StatePropertySet properties) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockProperties ->
                StatePropertyContext.construct(properties, () -> new DataDrivenBlock(blockProperties)))
                .withDefaultBlockItem(id);
    }
}
