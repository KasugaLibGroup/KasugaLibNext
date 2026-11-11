package test.kasuga.create.demo.preview;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import lib.kasuga.create.registration.track.TrackMaterialMetadata;
import lib.kasuga.create.registration.track.TrackMaterialMetadataIndex;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Exercises actual transformed legacy methods / modern constructors, not just mixin selection. */
final class CreateBezierClientCheck {
    static int run() {
        var material = TrackMaterial.ANDESITE;
        var previous = TrackMaterialMetadataIndex.get(material);
        int[] calls = {0};
        try {
            TrackMaterialMetadataIndex.put(material, new TrackMaterialMetadata(
                    ResourceLocation.parse("kasuga_demo:bezier_smoke"), false,
                    vector -> { calls[0]++; return vector.scale(.75); }));
            var curve = new BezierConnection(Couple.create(BlockPos.ZERO, new BlockPos(8, 0, 8)),
                    Couple.create(Vec3.ZERO, new Vec3(8, 0, 8)),
                    Couple.create(new Vec3(1, 0, 0), new Vec3(0, 0, -1)),
                    Couple.create(new Vec3(0, 1, 0), new Vec3(0, 1, 0)), true, true, material);
            curve.getBakedSegments();
            int segmentCalls = calls[0];
            curve.getBakedGirders();
            if (segmentCalls == 0 || calls[0] == segmentCalls)
                throw new IllegalStateException("Rail offsets were not applied to both Bezier paths: " + calls[0]);
            return calls[0];
        } finally {
            if (previous.isPresent()) TrackMaterialMetadataIndex.put(material, previous.get());
            else TrackMaterialMetadataIndex.remove(material);
        }
    }
}
