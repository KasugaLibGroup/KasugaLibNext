package lib.kasuga.mixins.modelling;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo @Mixin(value = SystemTimeUniforms.FrameCounter.class, remap = false)
public interface WorldViewIrisCounterAccessor {
    @Invoker("<init>") static SystemTimeUniforms.FrameCounter kasuga$create() { throw new AssertionError(); }
}
