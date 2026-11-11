package lib.kasuga.create.mixins.client;

import com.simibubi.create.content.trains.track.BezierConnection;
import lib.kasuga.create.registration.track.TrackMaterialMetadataIndex;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.simibubi.create.content.trains.track.BezierConnection$GirderAngles")
public abstract class ModernBezierGirdersMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 kasugaLib$applyGirderOffset(Vec3 vector, double factor, BezierConnection connection) {
        if (Math.abs(factor - .965d) > 1.0e-6d) return vector.scale(factor);
        return TrackMaterialMetadataIndex.get(connection.getMaterial())
                .map(metadata -> metadata.railOffset().apply(vector)).orElseGet(() -> vector.scale(factor));
    }
}
