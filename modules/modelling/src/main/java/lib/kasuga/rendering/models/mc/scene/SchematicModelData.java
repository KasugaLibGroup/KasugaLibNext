package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, read-only import of vanilla structure NBT and Litematica/Forgematica .litematic v5-v7. */
public final class SchematicModelData {
    public static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private SchematicModelData() {}

    /** File type is explicit: never guess that a foreign NBT format is a vanilla structure. */
    public static List<StructureBlockInfo> readStructure(Path file, HolderGetter<Block> blocks) throws IOException {
        return structure(NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES)), blocks, 0);
    }

    public static List<StructureBlockInfo> readLitematic(Path file, HolderGetter<Block> blocks) throws IOException {
        return litematic(NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES)), blocks);
    }

    public static List<StructureBlockInfo> structure(CompoundTag input, HolderGetter<Block> blocks, int paletteIndex) {
        if (!input.contains("blocks", Tag.TAG_LIST) || !input.contains("size", Tag.TAG_LIST))
            throw new IllegalArgumentException("Not a vanilla structure: missing blocks/size");
        CompoundTag data = fix(input);
        ListTag palette;
        if (data.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = data.getList("palettes", Tag.TAG_LIST);
            if (paletteIndex < 0 || paletteIndex >= palettes.size()) throw new IllegalArgumentException("Invalid structure palette");
            palette = (ListTag) palettes.get(paletteIndex);
        } else {
            if (paletteIndex != 0 || !data.contains("palette", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing structure palette");
            palette = data.getList("palette", Tag.TAG_COMPOUND);
        }
        var states = palette.stream().map(t -> {
            var state = (CompoundTag) t;
            var id = net.minecraft.resources.ResourceLocation.tryParse(state.getString("Name"));
            if (id == null || blocks.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, id)).isEmpty())
                throw new IllegalArgumentException("Schematic requires an unavailable block: " + state.getString("Name"));
            return NbtUtils.readBlockState(blocks, state);
        }).toList();
        ListTag entries = data.getList("blocks", Tag.TAG_COMPOUND);
        if (entries.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Structure exceeds block limit");
        var result = new ArrayList<StructureBlockInfo>(entries.size());
        for (Tag entry : entries) {
            var tag = (CompoundTag) entry;
            ListTag pos = tag.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3 || !tag.contains("state", Tag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException("Invalid structure block");
            int state = tag.getInt("state");
            if (state < 0 || state >= states.size()) throw new IllegalArgumentException("Invalid block palette index " + state);
            result.add(new StructureBlockInfo(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), states.get(state),
                    tag.contains("nbt", Tag.TAG_COMPOUND) ? tag.getCompound("nbt").copy() : null));
        }
        return List.copyOf(result);
    }

    public static List<StructureBlockInfo> structure(StructureTemplate template, HolderGetter<Block> blocks) {
        return structure(template.save(new CompoundTag()), blocks, 0);
    }

    public static List<StructureBlockInfo> litematic(CompoundTag root, HolderGetter<Block> blocks) {
        int version = root.getInt("Version");
        if (version < 5 || version > 7 || !root.contains("Regions", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Expected Litematica/Forgematica v5-v7 with Regions");
        int dataVersion = root.getInt("MinecraftDataVersion");
        if (dataVersion <= 0) throw new IllegalArgumentException("Litematic has no MinecraftDataVersion");
        Map<BlockPos, StructureBlockInfo> result = new LinkedHashMap<>();
        CompoundTag regions = root.getCompound("Regions");
        if (regions.getAllKeys().size() > 256) throw new IllegalArgumentException("Too many schematic regions");
        long totalVolume = 0;
        // Stable order makes overlapping subregion precedence repeatable.
        for (String name : regions.getAllKeys().stream().sorted().toList()) {
            CompoundTag region = regions.getCompound(name);
            BlockPos size = vector(region.getCompound("Size"));
            int sx = dimension(size.getX()), sy = dimension(size.getY()), sz = dimension(size.getZ());
            totalVolume += (long) sx * sy * sz;
            if (totalVolume > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Litematic volume exceeds block limit");
            BlockPos start = vector(region.getCompound("Position"));
            BlockPos minimum = start.offset(negativeOffset(size.getX()), negativeOffset(size.getY()), negativeOffset(size.getZ()));
            ListTag palette = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (palette.isEmpty() || palette.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Invalid litematic palette");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long[] packed = region.getLongArray("BlockStates");
            if (packed.length != ((long) sx * sy * sz * bits + 63) / 64) throw new IllegalArgumentException("Invalid litematic BlockStates length");
            Map<BlockPos, CompoundTag> entities = new LinkedHashMap<>();
            for (Tag entry : region.getList("TileEntities", Tag.TAG_COMPOUND)) {
                CompoundTag entity = (CompoundTag) entry;
                entities.put(vector(entity), entity);
            }
            CompoundTag vanilla = new CompoundTag();
            vanilla.putInt("DataVersion", dataVersion);
            vanilla.put("palette", palette.copy());
            vanilla.put("size", integers(sx, sy, sz));
            ListTag entries = new ListTag();
            for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
                int state = unpack(packed, ((long) y * sz + z) * sx + x, bits);
                if (state >= palette.size()) throw new IllegalArgumentException("Litematic references missing palette state");
                CompoundTag entry = new CompoundTag();
                entry.put("pos", integers(x, y, z));
                entry.putInt("state", state);
                CompoundTag entity = entities.get(new BlockPos(x, y, z));
                if (entity != null) entry.put("nbt", entity.copy());
                entries.add(entry);
            }
            vanilla.put("blocks", entries);
            for (var info : structure(vanilla, blocks, 0)) {
                BlockPos position = info.pos().offset(minimum);
                result.put(position, new StructureBlockInfo(position, info.state(), info.nbt()));
            }
        }
        return List.copyOf(result.values());
    }

    /** Litematica packs continuously; entries can straddle two longs (unlike modern chunk storage). */
    static int unpack(long[] values, long index, int bits) {
        if (index < 0 || bits < 2 || bits > 31) throw new IllegalArgumentException("Invalid packed block index/bits");
        long bit = Math.multiplyExact(index, bits);
        int word = Math.toIntExact(bit >>> 6);
        int shift = (int) (bit & 63);
        if (word >= values.length || shift + bits > 64 && word + 1 >= values.length)
            throw new IllegalArgumentException("Truncated packed block states");
        long value = values[word] >>> shift;
        if (shift + bits > 64) value |= values[word + 1] << (64 - shift);
        return (int) (value & ((1L << bits) - 1));
    }

    static int negativeOffset(int signedSize) { return signedSize < 0 ? signedSize + 1 : 0; }
    private static int dimension(int value) {
        if (value == 0 || value < -MinecraftModelSources.MAX_SPAN || value > MinecraftModelSources.MAX_SPAN)
            throw new IllegalArgumentException("Invalid schematic dimension");
        return Math.abs(value);
    }
    private static BlockPos vector(CompoundTag tag) {
        if (!tag.contains("x", Tag.TAG_ANY_NUMERIC) || !tag.contains("y", Tag.TAG_ANY_NUMERIC) || !tag.contains("z", Tag.TAG_ANY_NUMERIC))
            throw new IllegalArgumentException("Incomplete schematic position/size");
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }
    private static ListTag integers(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x)); list.add(IntTag.valueOf(y)); list.add(IntTag.valueOf(z));
        return list;
    }
    private static CompoundTag fix(CompoundTag input) {
        int version = NbtUtils.getDataVersion(input, 500);
        if (version > SharedConstants.getCurrentVersion().getDataVersion().getVersion())
            throw new IllegalArgumentException("Schematic comes from a newer Minecraft version");
        return DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), input.copy(), version);
    }
}
