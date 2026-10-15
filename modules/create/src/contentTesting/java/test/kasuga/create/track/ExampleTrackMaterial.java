package test.kasuga.create.track;

import com.simibubi.create.Create;
import lib.kasuga.create.registration.track.TrackMaterialReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

public final class ExampleTrackMaterial {
    public static final TrackMaterialReg STANDARD = new TrackMaterialReg("standard")
            .lang("Standard")
            .particle(Create.asResource("block/palettes/stone_types/polished/andesite_cut_polished"))
            .customModels(
                    model("standard/tie"),
                    model("standard/segment_left"),
                    model("standard/segment_right")
            )
            .simpleTrackModelOffset(0.755f)
            .sleeper(Blocks.ANDESITE_SLAB);

    public static final TrackMaterialReg TIELESS = new TrackMaterialReg("tieless")
            .lang("Tieless")
            .particle(Create.asResource("block/palettes/stone_types/polished/andesite_cut_polished"))
            .customModels(
                    ResourceLocation.fromNamespaceAndPath("kasuga_lib", "empty_model"),
                    model("tieless/segment_left"),
                    model("tieless/segment_right")
            )
            .simpleTrackModelOffset(0.755f)
            .sleeper(Blocks.ANDESITE_SLAB);

    private ExampleTrackMaterial() {
    }

    private static ResourceLocation model(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", "block/track/" + path);
    }
}
