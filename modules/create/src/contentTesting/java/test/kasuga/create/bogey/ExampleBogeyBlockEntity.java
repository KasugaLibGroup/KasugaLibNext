package test.kasuga.create.bogey;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

public final class ExampleBogeyBlockEntity extends AbstractBogeyBlockEntity {
    private final Supplier<BogeyStyle> defaultStyle;

    public ExampleBogeyBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            Supplier<BogeyStyle> defaultStyle
    ) {
        super(type, pos, state);
        this.defaultStyle = defaultStyle;
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return defaultStyle.get();
    }
}
