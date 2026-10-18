package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.data.DataProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class BlockProxy<ProxiedType extends Block, ProxiedInstanceType extends BlockState> implements ElementProxy<ProxiedType, ProxiedInstanceType> {

    private final ProxiedType block;

    public BlockProxy(ProxiedType block) {
        this.block = block;
    }

    @Override
    public boolean isValidInput(Object input) {
        return Objects.equals(input, block);
    }

    @Override
    public boolean isValidInstance(Object instance) {
        if (!(instance instanceof BlockState state)) return false;
        return state.is(block);
    }

    @Override
    public @Nullable DataProvider getDataProvider(ProxiedInstanceType instance, Object... externalData) {
        if (!isValidInstance(instance)) return null;
        if (externalData.length < 2) return null;
        if (!(externalData[0] instanceof BlockPos pos)) return null;
        if (!(externalData[1] instanceof Level level)) return null;

        return new BlockDataProvider(level, pos, instance, false);
    }
}
