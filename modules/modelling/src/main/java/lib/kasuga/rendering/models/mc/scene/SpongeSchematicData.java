package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import java.util.*;

/** Sponge/WorldEdit .schem v2/v3 converted through the existing vanilla structure data-fixer.
 * Schema: https://github.com/SpongePowered/Schematic-Specification/tree/master/versions
 * Entities/biomes are not world objects in a block-model preview; block-entity NBT is retained.
 */
public final class SpongeSchematicData {
    private SpongeSchematicData() {}
    public static List<StructureBlockInfo> decode(CompoundTag input,HolderGetter<Block> blocks) {
        var root=input.contains("Schematic",Tag.TAG_COMPOUND)?input.getCompound("Schematic"):input;
        int version=root.getInt("Version");
        if(version!=2&&version!=3)throw new IllegalArgumentException("Expected Sponge schematic v2/v3");
        if(root.getInt("DataVersion")<=0)throw new IllegalArgumentException("Sponge schematic has no DataVersion");
        int sx=dimension(root,"Width"),sy=dimension(root,"Height"),sz=dimension(root,"Length");
        long volume=(long)sx*sy*sz;
        if(volume>MinecraftModelSources.MAX_BLOCKS)throw new IllegalArgumentException("Sponge schematic volume exceeds block limit");
        var container=version==3?root.getCompound("Blocks"):root;
        var palette=container.getCompound("Palette");
        if(palette.isEmpty()||palette.size()>MinecraftModelSources.MAX_BLOCKS)throw new IllegalArgumentException("Invalid Sponge palette");
        var compact=new HashMap<Integer,Integer>();var states=new ListTag();
        for(var name:palette.getAllKeys().stream().sorted().toList()) {
            if(!palette.contains(name,Tag.TAG_INT)||palette.getInt(name)<0)throw new IllegalArgumentException("Invalid Sponge palette index");
            if(compact.put(palette.getInt(name),states.size())!=null)throw new IllegalArgumentException("Duplicate Sponge palette index");
            states.add(stateTag(name));
        }
        String dataKey=version==3?"Data":"BlockData";
        if(!container.contains(dataKey,Tag.TAG_BYTE_ARRAY))throw new IllegalArgumentException("Missing Sponge block data");
        int[] indices=decodeVarints(container.getByteArray(dataKey),(int)volume);
        int[] offset=root.contains("Offset",Tag.TAG_INT_ARRAY)?root.getIntArray("Offset"):new int[]{0,0,0};
        if(offset.length!=3)throw new IllegalArgumentException("Invalid Sponge offset");
        var entities=new HashMap<BlockPos,CompoundTag>();
        for(var tag:container.getList("BlockEntities",Tag.TAG_COMPOUND)) {
            var entity=(CompoundTag)tag;int[] pos=entity.getIntArray("Pos");
            if(pos.length!=3||pos[0]<0||pos[0]>=sx||pos[1]<0||pos[1]>=sy||pos[2]<0||pos[2]>=sz)
                throw new IllegalArgumentException("Invalid Sponge block entity position");
            var data=version==3?entity.getCompound("Data").copy():entity.copy();
            data.remove("Pos");data.remove("Id");data.putString("id",entity.getString("Id"));
            var position=new BlockPos(pos[0],pos[1],pos[2]);
            if(entities.put(position,data)!=null)throw new IllegalArgumentException("Duplicate Sponge block entity");
        }
        var entries=new ListTag();
        for(int i=0;i<indices.length;i++) {
            Integer state=compact.get(indices[i]);if(state==null)throw new IllegalArgumentException("Sponge references unknown palette index");
            int x=i%sx,z=i/sx%sz,y=i/(sx*sz);
            var entry=new CompoundTag();entry.put("pos",ints(x,y,z));entry.putInt("state",state);
            var nbt=entities.get(new BlockPos(x,y,z));if(nbt!=null)entry.put("nbt",nbt);
            entries.add(entry);
        }
        var vanilla=new CompoundTag();vanilla.putInt("DataVersion",root.getInt("DataVersion"));
        vanilla.put("size",ints(sx,sy,sz));vanilla.put("palette",states);vanilla.put("blocks",entries);
        return SchematicModelData.structure(vanilla,blocks,0).stream().map(info->{
            var p=info.pos();var position=new BlockPos(Math.addExact(p.getX(),offset[0]),Math.addExact(p.getY(),offset[1]),Math.addExact(p.getZ(),offset[2]));
            return new StructureBlockInfo(position,info.state(),info.nbt());
        }).toList();
    }
    static int[] decodeVarints(byte[] data,int count) {
        if(count<0||count>MinecraftModelSources.MAX_BLOCKS)throw new IllegalArgumentException("Invalid Sponge data length");
        var result=new int[count];int cursor=0;
        for(int i=0;i<count;i++) {
            long value=0;int shift=0;
            while(true) {
                if(cursor>=data.length||shift>28)throw new IllegalArgumentException("Truncated/oversized Sponge varint");
                int b=data[cursor++]&255;value|=(long)(b&127)<<shift;
                if(value>Integer.MAX_VALUE)throw new IllegalArgumentException("Sponge varint overflow");
                if((b&128)==0)break;shift+=7;
            }
            result[i]=(int)value;
        }
        if(cursor!=data.length)throw new IllegalArgumentException("Trailing Sponge block data");
        return result;
    }
    private static CompoundTag stateTag(String encoded) {
        var tag=new CompoundTag();int bracket=encoded.indexOf('[');
        if(bracket<0) { tag.putString("Name",encoded);return tag; }
        if(!encoded.endsWith("]"))throw new IllegalArgumentException("Invalid Sponge block state");
        tag.putString("Name",encoded.substring(0,bracket));var props=new CompoundTag();
        String body=encoded.substring(bracket+1,encoded.length()-1);
        if(!body.isEmpty())for(String pair:body.split(",",-1)) {
            String[] value=pair.split("=",-1);
            if(value.length!=2||value[0].isEmpty()||value[1].isEmpty()||props.contains(value[0]))throw new IllegalArgumentException("Invalid Sponge property");
            props.putString(value[0],value[1]);
        }
        tag.put("Properties",props);return tag;
    }
    private static int dimension(CompoundTag root,String name) {
        if(!root.contains(name,Tag.TAG_SHORT))throw new IllegalArgumentException("Missing Sponge dimension "+name);
        int size=root.getShort(name)&65535;
        if(size==0||size>MinecraftModelSources.MAX_SPAN)throw new IllegalArgumentException("Invalid Sponge dimension");
        return size;
    }
    private static ListTag ints(int x,int y,int z) {var list=new ListTag();list.add(IntTag.valueOf(x));list.add(IntTag.valueOf(y));list.add(IntTag.valueOf(z));return list;}
}
