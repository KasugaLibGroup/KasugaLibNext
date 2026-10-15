package lib.kasuga.mixins.modelling;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo @Mixin(value = CapturedRenderingState.class, remap = false)
public interface WorldViewIrisStateAccessor {
    @Invoker("<init>") static CapturedRenderingState kasuga$create() { throw new AssertionError(); }
    @Mutable @Accessor("INSTANCE") static void kasuga$setInstance(CapturedRenderingState state) { throw new AssertionError(); }
}
