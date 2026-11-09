package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Registry/data-fixer acceptance belongs to NeoForge's test runtime; plain modelUnitTest skips it. */
class SchematicImportTest {
    @BeforeAll static void runtime() { assumeTrue(LoadingModList.get() != null, "Requires the real FML registry/data-fixer runtime"); }

    @Test void readsTheBundledEndCityBuildingWithoutChangingItsNbt(@TempDir java.nio.file.Path directory) throws Exception {
        try (var pack = net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource()) {
            var supplier = pack.getResource(net.minecraft.server.packs.PackType.SERVER_DATA,
                    net.minecraft.resources.ResourceLocation.parse("minecraft:structure/end_city/base_floor.nbt"));
            assertNotNull(supplier, "Minecraft must provide the building fixture");
            try (var input = supplier.get()) {
                CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES));
                CompoundTag before = root.copy();
                var data = SchematicModelData.structure(root, BuiltInRegistries.BLOCK.asLookup(), 0);
                assertEquals(root.getList("blocks", Tag.TAG_COMPOUND).size(), data.size());
                assertTrue(data.size() > 100);
                assertTrue(data.stream().anyMatch(info -> info.state().is(Blocks.PURPUR_BLOCK)));
                assertEquals(before, root, "Read-only import modified the building fixture");
                var path = directory.resolve("building.nbt"); NbtIo.writeCompressed(root, path);
                assertEquals(data, SchematicModelData.readStructure(path, BuiltInRegistries.BLOCK.asLookup()));
            }
        }
    }

    @Test void litematicNegativeSizesAndBlockEntityDataStayInTheSameFrame(@TempDir java.nio.file.Path directory) throws Exception {
        CompoundTag root = new CompoundTag(); root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        CompoundTag region = new CompoundTag(); region.put("Position", vector(8, -3, 4)); region.put("Size", vector(-2, 2, -1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.RED_CONCRETE.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));
        region.put("BlockStatePalette", palette); region.putLongArray("BlockStates", new long[]{1 | 2 << 2 | 1 << 4});
        ListTag entities = new ListTag(); CompoundTag chest = vector(1, 0, 0); chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "\"Test chest\""); entities.add(chest); region.put("TileEntities", entities);
        CompoundTag regions = new CompoundTag(); regions.put("backwards", region); root.put("Regions", regions);
        var data = SchematicModelData.litematic(root, BuiltInRegistries.BLOCK.asLookup());
        assertEquals(4, data.size());
        var placedChest = data.stream().filter(info -> info.state().is(Blocks.CHEST)).findFirst().orElseThrow();
        assertEquals(new BlockPos(8, -3, 4), placedChest.pos());
        assertEquals("\"Test chest\"", placedChest.nbt().getString("CustomName"));
        assertEquals(1, chest.getInt("x"), "Importer must not mutate source block-entity positions");
        var path = directory.resolve("negative.litematic"); NbtIo.writeCompressed(root, path);
        assertEquals(data, SchematicModelData.readLitematic(path, BuiltInRegistries.BLOCK.asLookup()));
    }

    @Test void rejectsMissingModsAndCorruptPaletteReferences() {
        CompoundTag root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("size", integers(1, 1, 1));
        ListTag palette = new ListTag(); CompoundTag missing = new CompoundTag(); missing.putString("Name", "not_installed:machine"); palette.add(missing);
        root.put("palette", palette); root.put("blocks", new ListTag());
        assertThrows(IllegalArgumentException.class, () -> SchematicModelData.structure(root, BuiltInRegistries.BLOCK.asLookup(), 0));
        palette.set(0, NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag entries = new ListTag(); var invalid = new CompoundTag(); invalid.put("pos", integers(0, 0, 0)); invalid.putInt("state", 2);
        entries.add(invalid); root.put("blocks", entries);
        assertThrows(IllegalArgumentException.class, () -> SchematicModelData.structure(root, BuiltInRegistries.BLOCK.asLookup(), 0));
    }

    private static CompoundTag vector(int x, int y, int z) { var t = new CompoundTag(); t.putInt("x", x); t.putInt("y", y); t.putInt("z", z); return t; }
    private static ListTag integers(int x, int y, int z) { var t = new ListTag(); t.add(IntTag.valueOf(x)); t.add(IntTag.valueOf(y)); t.add(IntTag.valueOf(z)); return t; }
}
