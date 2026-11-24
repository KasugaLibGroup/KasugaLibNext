package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.models.mc.dynamic.collision.MinecraftModelCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(Level.class)
abstract class ModelCollisionLevelMixin implements LevelAccessor {
    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB area) {
        List<VoxelShape> vanilla = LevelAccessor.super.getEntityCollisions(entity, area);
        return MinecraftModelCollisions.append((Level) (Object) this, entity, area, vanilla);
    }
}
