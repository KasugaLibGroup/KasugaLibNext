package lib.kasuga.mixins.modelling;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FogRenderer.class)
public interface WorldViewFogAccessor {
    @Accessor("fogRed") static float kasuga$red() { throw new AssertionError(); }
    @Accessor("fogRed") static void kasuga$red(float value) { throw new AssertionError(); }
    @Accessor("fogGreen") static float kasuga$green() { throw new AssertionError(); }
    @Accessor("fogGreen") static void kasuga$green(float value) { throw new AssertionError(); }
    @Accessor("fogBlue") static float kasuga$blue() { throw new AssertionError(); }
    @Accessor("fogBlue") static void kasuga$blue(float value) { throw new AssertionError(); }
    @Accessor("targetBiomeFog") static int kasuga$target() { throw new AssertionError(); }
    @Accessor("targetBiomeFog") static void kasuga$target(int value) { throw new AssertionError(); }
    @Accessor("previousBiomeFog") static int kasuga$previous() { throw new AssertionError(); }
    @Accessor("previousBiomeFog") static void kasuga$previous(int value) { throw new AssertionError(); }
    @Accessor("biomeChangedTime") static long kasuga$changedAt() { throw new AssertionError(); }
    @Accessor("biomeChangedTime") static void kasuga$changedAt(long value) { throw new AssertionError(); }
}
