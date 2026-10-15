package test.kasuga.create.track;

import com.simibubi.create.AllTags;
import lib.kasuga.create.registration.track.TrackReg;
import lib.kasuga.registration.Registry;
import net.minecraft.resources.ResourceLocation;
import test.kasuga.create.bogey.AllExampleBogey;

public final class ExampleTracks {
    public static final TrackReg<ExampleTrackBlock> STANDARD_TRACK = new TrackReg<>(
            "standard_track",
            ExampleTrackMaterial.STANDARD,
            (properties, material) -> new ExampleTrackBlock(properties, material, AllExampleBogey.STANDARD_BOGEY::getEntry)
    ).withBlockProperties(properties -> properties.strength(1.0f).noOcclusion());

    public static final TrackReg<ExampleTrackBlock> TIELESS_TRACK = new TrackReg<>(
            "tieless_track",
            ExampleTrackMaterial.TIELESS,
            (properties, material) -> new ExampleTrackBlock(properties, material, AllExampleBogey.CW2_BOGEY::getEntry)
    ).withBlockProperties(properties -> properties.strength(1.0f).noOcclusion());

    private static boolean registered;

    private ExampleTracks() {
    }

    public static synchronized void register(Registry registry) {
        if (registered) {
            return;
        }
        registered = true;

        ExampleTrackMaterial.STANDARD
                .block(STANDARD_TRACK)
                .trackType(
                        ResourceLocation.fromNamespaceAndPath(registry.getModId(), "standard"),
                        (properties, material) -> new ExampleTrackBlock(
                                properties,
                                material,
                                AllExampleBogey.STANDARD_BOGEY::getEntry
                        )
                )
                .setParent(registry);
        ExampleTrackMaterial.TIELESS
                .block(TIELESS_TRACK)
                .trackType(
                        ResourceLocation.fromNamespaceAndPath(registry.getModId(), "tieless"),
                        (properties, material) -> new ExampleTrackBlock(
                                properties,
                                material,
                                AllExampleBogey.CW2_BOGEY::getEntry
                        )
                )
                .setParent(registry);
        STANDARD_TRACK.setParent(registry);
        TIELESS_TRACK.setParent(registry);
    }
}
