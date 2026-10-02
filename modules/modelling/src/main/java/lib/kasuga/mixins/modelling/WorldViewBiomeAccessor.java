package lib.kasuga.mixins.modelling;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(BiomeManager.class) public interface WorldViewBiomeAccessor {
    @Accessor("biomeZoomSeed") long kasuga$seed();
}
