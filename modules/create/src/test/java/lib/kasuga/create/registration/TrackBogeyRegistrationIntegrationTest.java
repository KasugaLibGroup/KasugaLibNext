package lib.kasuga.create.registration;

import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.track.TrackBlockItem;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.create.registration.bogey.BogeyMetadataIndex;
import lib.kasuga.create.registration.track.TrackMaterialMetadataIndex;
import lib.kasuga.registration.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EphemeralTestServerProvider.class)
class TrackBogeyRegistrationIntegrationTest {
    private static final Registry REGISTRY = KasugaLibApplication.REGISTRY;
    private static final ResourceLocation TRACK_MATERIAL_ID = id("standard");
    private static final ResourceLocation TIELESS_MATERIAL_ID = id("tieless");
    private static final ResourceLocation TRACK_ID = id("standard_track");
    private static final ResourceLocation TIELESS_TRACK_ID = id("tieless_track");
    private static final ResourceLocation BOGEY_SIZE_ID = id("pk209p");
    private static final ResourceLocation CW2_SIZE_ID = id("cw2");
    private static final ResourceLocation BOGEY_BLOCK_ID = id("pk209p_bogey");
    private static final ResourceLocation CW2_BLOCK_ID = id("cw2_bogey");
    private static final ResourceLocation BOGEY_STYLE_ID = id("standard");
    private static final ResourceLocation CW2_STYLE_ID = id("test");

    @Test
    void registersTrackMaterialBlockAndItem(MinecraftServer server) {
        assertTrue(BuiltInRegistries.BLOCK.containsKey(TRACK_ID));
        assertTrue(BuiltInRegistries.ITEM.containsKey(TRACK_ID));
        assertTrue(BuiltInRegistries.BLOCK.containsKey(TIELESS_TRACK_ID));
        assertTrue(BuiltInRegistries.ITEM.containsKey(TIELESS_TRACK_ID));
        assertTrue(BuiltInRegistries.ITEM.get(TRACK_ID) instanceof TrackBlockItem);
        assertTrue(BuiltInRegistries.ITEM.get(TIELESS_TRACK_ID) instanceof TrackBlockItem);
        var material = com.simibubi.create.content.trains.track.TrackMaterial.ALL.get(TRACK_MATERIAL_ID);
        var tieless = com.simibubi.create.content.trains.track.TrackMaterial.ALL.get(TIELESS_MATERIAL_ID);
        assertSame(BuiltInRegistries.BLOCK.get(TRACK_ID), material.getBlock());
        assertSame(BuiltInRegistries.BLOCK.get(TIELESS_TRACK_ID), tieless.getBlock());
        assertEquals(TRACK_MATERIAL_ID, material.id);
        assertEquals(TIELESS_MATERIAL_ID, tieless.id);
        assertEquals(TRACK_MATERIAL_ID, TrackMaterialMetadataIndex.get(material).orElseThrow().id());
        assertEquals(TIELESS_MATERIAL_ID, TrackMaterialMetadataIndex.get(tieless).orElseThrow().id());
    }

    @Test
    void registersBogeyDependenciesAsOneCommittedCatalog(MinecraftServer server) {
        var size = com.simibubi.create.content.trains.bogey.BogeySizes.all().get(BOGEY_SIZE_ID);
        var block = BuiltInRegistries.BLOCK.get(BOGEY_BLOCK_ID);
        var style = AllBogeyStyles.BOGEY_STYLES.get(BOGEY_STYLE_ID);
        var cw2Size = com.simibubi.create.content.trains.bogey.BogeySizes.all().get(CW2_SIZE_ID);
        var cw2Block = BuiltInRegistries.BLOCK.get(CW2_BLOCK_ID);
        var cw2Style = AllBogeyStyles.BOGEY_STYLES.get(CW2_STYLE_ID);
        assertTrue(style.validSizes().contains(size));
        assertSame(block, style.getBlockForSize(size));
        assertTrue(cw2Style.validSizes().contains(cw2Size));
        assertSame(cw2Block, cw2Style.getBlockForSize(cw2Size));
        assertEquals(BOGEY_SIZE_ID, BogeyMetadataIndex.get(size).orElseThrow().id());
        assertEquals(CW2_SIZE_ID, BogeyMetadataIndex.get(cw2Size).orElseThrow().id());
        assertEquals(BOGEY_STYLE_ID, BogeyMetadataIndex.get(style).orElseThrow().id());
        assertEquals(CW2_STYLE_ID, BogeyMetadataIndex.get(cw2Style).orElseThrow().id());
        assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id("standard_bogey_entity")));
        assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id("test_bogey_entity")));
    }

    @Test
    void dedicatedServerCommitsOnceWithoutClientBridge(MinecraftServer server) {
        CreateRegistrationState state = REGISTRY.getAttachment(CreateRegistrationState.class).orElseThrow();
        assertEquals(CreateRegistrationState.Status.COMMITTED, state.status());
        assertEquals(1, state.preparationCount());
        assertFalse(REGISTRY.getAttachment(CreateClientBridge.class).isPresent());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", path);
    }
}
