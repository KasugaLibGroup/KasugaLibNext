package test.kasuga.create.demo.preview;

import lib.kasuga.create.client.model.CreateModelSources;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.kinetics.drill.DrillBlock;
import lib.kasuga.KasugaLib;
import test.kasuga.modelling.demo.preview.MinecraftPreviewDemo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.apache.commons.lang3.tuple.MutablePair;

import java.util.List;

/** Exercises real Create block-entity/actor renderers without spawning or assembling a game entity. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateModelPreviewDemo {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        MinecraftPreviewDemo.register("create-schematic", () -> {
            var mc = Minecraft.getInstance();
            var dir = mc.gameDirectory.toPath().resolve("schematics");
            java.nio.file.Files.createDirectories(dir);
            NbtIo.writeCompressed(template().save(new CompoundTag()), dir.resolve("kasuga-preview-fixture.nbt"));
            var item = com.simibubi.create.content.schematics.SchematicItem.create(mc.level, "kasuga-preview-fixture.nbt", "preview");
            return CreateModelSources.schematic(mc.level, item);
        });
        MinecraftPreviewDemo.register("create-contraption-actor", () -> {
            var level = Minecraft.getInstance().level;
            var contraption = new MountedContraption();
            contraption.anchor = BlockPos.ZERO; contraption.bounds = new AABB(-1, 0, 0, 2, 4, 1);
            for (var info : blocks()) contraption.getBlocks().put(info.pos(), info);
            var drill = new StructureBlockInfo(new BlockPos(0, 2, 0),
                    AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(DrillBlock.FACING, Direction.SOUTH), new CompoundTag());
            contraption.getBlocks().put(drill.pos(), drill);
            var actor = new MovementContext(level, drill, contraption);
            actor.motion = new Vec3(.2, 0, 0); actor.relativeMotion = new Vec3(.2, 0, 0);
            contraption.getActors().add(MutablePair.of(drill, actor));
            var entity = OrientedContraptionEntity.create(level, contraption, Direction.SOUTH);
            contraption.entity = entity;
            entity.setPos(0, 64, 0);
            return CreateModelSources.contraption(entity);
        });
        MinecraftPreviewDemo.register("create-airship", CreateAirshipExample::create);
        String path = System.getProperty("kasuga.minecraftPreview.schematic");
        if (path != null && !path.isBlank()) MinecraftPreviewDemo.register("external-create-schematic",
                () -> CreateModelSources.schematic(Minecraft.getInstance().level, java.nio.file.Path.of(path)));
    }

    private static List<StructureBlockInfo> blocks() {
        return List.of(new StructureBlockInfo(new BlockPos(0, 0, 0), AllBlocks.COGWHEEL.getDefaultState(), null),
                new StructureBlockInfo(new BlockPos(0, 1, 0), AllBlocks.ANDESITE_CASING.getDefaultState(), null),
                new StructureBlockInfo(new BlockPos(0, 3, 0), Blocks.GLASS.defaultBlockState(), null));
    }
    private static StructureTemplate template() {
        var root = new CompoundTag();
        root.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        var size = new ListTag(); size.add(IntTag.valueOf(1)); size.add(IntTag.valueOf(4)); size.add(IntTag.valueOf(1)); root.put("size", size);
        var palette = new ListTag(); var entries = new ListTag();
        for (var info : blocks()) {
            var block = new CompoundTag();
            var pos = new ListTag(); pos.add(IntTag.valueOf(info.pos().getX())); pos.add(IntTag.valueOf(info.pos().getY())); pos.add(IntTag.valueOf(info.pos().getZ()));
            block.put("pos", pos); block.putInt("state", palette.size());
            palette.add(NbtUtils.writeBlockState(info.state())); entries.add(block);
        }
        root.put("palette", palette); root.put("blocks", entries);
        var template = new StructureTemplate(); template.load(Minecraft.getInstance().level.holderLookup(Registries.BLOCK), root); return template;
    }
}
