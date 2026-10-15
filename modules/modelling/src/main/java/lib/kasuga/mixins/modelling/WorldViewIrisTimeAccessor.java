package lib.kasuga.mixins.modelling;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo @Mixin(value = SystemTimeUniforms.class, remap = false)
public interface WorldViewIrisTimeAccessor {
    @Mutable @Accessor("TIMER") static void kasuga$setTimer(SystemTimeUniforms.Timer timer) { throw new AssertionError(); }
    @Mutable @Accessor("COUNTER") static void kasuga$setCounter(SystemTimeUniforms.FrameCounter counter) { throw new AssertionError(); }
}
