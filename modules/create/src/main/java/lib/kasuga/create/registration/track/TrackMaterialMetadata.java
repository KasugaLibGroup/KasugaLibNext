package lib.kasuga.create.registration.track;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

public record TrackMaterialMetadata(
        ResourceLocation id,
        boolean replaceable,
        UnaryOperator<Vec3> railOffset
) {
}
