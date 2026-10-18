package lib.kasuga.core.rendering;

import org.spongepowered.asm.mixin.Unique;

public interface IBlurControl {

    boolean ksgB$isBlurEnabled();

    void ksg$SetBlurEnabled(boolean ksgBlurEnabled);
}
