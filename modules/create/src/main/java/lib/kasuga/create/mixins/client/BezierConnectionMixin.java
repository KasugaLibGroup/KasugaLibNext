package lib.kasuga.create.mixins.client;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import lib.kasuga.create.registration.track.TrackMaterialMetadataIndex;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BezierConnection.class)
public abstract class BezierConnectionMixin {
    @Shadow(remap = false)
    protected TrackMaterial trackMaterial;

    @Redirect(
            method = {"getBakedSegments", "getBakedGirders"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 kasugaLib$applyTrackRailOffset(Vec3 vector, double factor) {
        if (Math.abs(factor - .965d) > 1.0e-6d) {
            return vector.scale(factor);
        }
        return TrackMaterialMetadataIndex.get(trackMaterial)
                .map(metadata -> metadata.railOffset().apply(vector))
                .orElseGet(() -> vector.scale(factor));
    }
}
