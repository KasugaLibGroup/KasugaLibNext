package lib.kasuga.registration.data_driven.state;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/** Opt-in factory contract for block types that install data-defined state properties. */
@FunctionalInterface
public interface StatefulBlockFactory extends FactoryRegistry.BlockFactory {
    Reg<?, Block> create(String id, @Nullable JsonObject params, StatePropertySet properties);

    @Override
    default Reg<?, Block> create(String id, @Nullable JsonObject params) {
        return create(id, params, StatePropertySet.empty());
    }
}
