package lib.kasuga.rendering.models.mc.content.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class KasugaBlockEntity extends BlockEntity {

    protected final KasugaRenderComponent kasugaRender = new KasugaRenderComponent(this);

    public KasugaBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        kasugaRender.onLoad();
    }

    @Override
    public void setRemoved() {
        kasugaRender.onRemove();
        super.setRemoved();
    }

    public KasugaRenderComponent getKasugaRender() {
        return kasugaRender;
    }
}
