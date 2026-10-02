package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;

/** Iris 1.8.12 injects a static format flag; each CloudRenderer owns its own geometry. */
@Pseudo @Mixin(value = CloudRenderer.class, remap = false, priority = 900)
abstract class WorldViewIrisCloudMixin {
    @Unique private static java.lang.invoke.VarHandle kasuga$formatFlag;
    @Unique private static java.lang.invoke.VarHandle kasuga$formatFlag() {
        if (kasuga$formatFlag != null) return kasuga$formatFlag;
        // Iris adds the private field after mixin preprocessing, so @Shadow cannot resolve it.
        for (var field : CloudRenderer.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && field.getName().endsWith("hadShadersOn")) {
                try { return kasuga$formatFlag = java.lang.invoke.MethodHandles.lookup()
                        .findStaticVarHandle(CloudRenderer.class, field.getName(), boolean.class); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot isolate Iris cloud format", failure); }
            }
        }
        throw new IllegalStateException("Iris cloud format field is unavailable");
    }
    @Unique private boolean kasuga$hadShadersOn;
    @WrapMethod(method = "render")
    private void kasuga$cloudFormat(Camera camera, ClientLevel level, Matrix4f projection,
                                    PoseStack pose, float ticks, float partial, Operation<Void> original) {
        var flag = kasuga$formatFlag();
        boolean previous = (boolean) flag.get();
        flag.set(kasuga$hadShadersOn);
        try { original.call(camera, level, projection, pose, ticks, partial); }
        finally { kasuga$hadShadersOn = (boolean) flag.get(); flag.set(previous); }
    }
}
