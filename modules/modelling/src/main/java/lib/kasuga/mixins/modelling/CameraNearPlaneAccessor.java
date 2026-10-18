package lib.kasuga.mixins.modelling;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.NearPlane.class)
public interface CameraNearPlaneAccessor {
    @Invoker("<init>")
    static Camera.NearPlane kasuga$create(Vec3 forward, Vec3 left, Vec3 up) {
        throw new AssertionError("Mixin not applied");
    }
}
