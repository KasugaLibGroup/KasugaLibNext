package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/** Content-detected, bounded blueprint import. Mods can register additional NBT formats without a UI dependency. */
public final class BlueprintImporters {
    @FunctionalInterface public interface Decoder {
        List<StructureBlockInfo> decode(CompoundTag root, HolderGetter<Block> blocks);
    }
    private record Format(ResourceLocation id, Predicate<CompoundTag> matches, Decoder decoder) {}
    private static final List<Format> FORMATS=new ArrayList<>();
    static {
        register(ResourceLocation.parse("minecraft:structure"),r->r.contains("blocks",Tag.TAG_LIST)&&r.contains("size",Tag.TAG_LIST),(r,b)->SchematicModelData.structure(r,b,0));
        register(ResourceLocation.parse("kasuga_lib:litematic"),r->r.contains("Regions",Tag.TAG_COMPOUND),SchematicModelData::litematic);
        register(ResourceLocation.parse("kasuga_lib:sponge"),r->r.contains("Schematic",Tag.TAG_COMPOUND)||r.contains("Width",Tag.TAG_SHORT),SpongeSchematicData::decode);
    }
    private BlueprintImporters() {}
    public static synchronized void register(ResourceLocation id, Predicate<CompoundTag> matches, Decoder decoder) {
        Objects.requireNonNull(id);Objects.requireNonNull(matches);Objects.requireNonNull(decoder);
        if(FORMATS.stream().anyMatch(f->f.id.equals(id)))throw new IllegalArgumentException("Duplicate blueprint format "+id);
        FORMATS.add(new Format(id,matches,decoder));
    }
    /** Reads gzip or uncompressed NBT regardless of the filename extension. Never modifies the input file. */
    public static List<StructureBlockInfo> read(Path path, HolderGetter<Block> blocks) throws IOException {
        if(Files.size(path)>SchematicModelData.MAX_NBT_BYTES)throw new IOException("Blueprint exceeds input byte limit");
        try(var raw=new BufferedInputStream(Files.newInputStream(path))) {
            raw.mark(2);int a=raw.read(),b=raw.read();raw.reset();
            InputStream input=a==0x1f&&b==0x8b?new GZIPInputStream(raw):raw;
            try(var data=new DataInputStream(input)) {
                return decode(NbtIo.read(data,NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES)),blocks);
            }
        }
    }
    public static List<StructureBlockInfo> decode(CompoundTag root, HolderGetter<Block> blocks) {
        List<Format> formats;
        synchronized(BlueprintImporters.class){formats=List.copyOf(FORMATS);}
        for(var format:formats)if(format.matches.test(root)) {
            var result=List.copyOf(format.decoder.decode(root,blocks));
            if(result.size()>MinecraftModelSources.MAX_BLOCKS)throw new IllegalArgumentException("Too many blueprint blocks");
            return result;
        }
        throw new IllegalArgumentException("Unknown blueprint format; install/register an importer for this format");
    }
    public static BlockModelSource load(Level host,Path path) throws IOException {
        return MinecraftModelSources.blocks(host,read(path,host.holderLookup(Registries.BLOCK)));
    }
}
