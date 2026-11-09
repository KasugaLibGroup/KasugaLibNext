package test.kasuga.create.demo.preview;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import lib.kasuga.create.client.model.CreateModelSources;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.MutablePair;

import java.util.LinkedHashMap;
import java.util.List;

/** A detached flying drill rig: native Create geometry, kinetic block entities and movement actors. */
public final class CreateAirshipExample {
    private CreateAirshipExample() {}
    public static MinecraftModelSource create() {
        var level = Minecraft.getInstance().level;
        var blocks = new LinkedHashMap<BlockPos, StructureBlockInfo>();
        // Open lower deck with a brass perimeter and exposed undercarriage.
        for (int x = -3; x <= 3; x++) for (int z = -6; z <= 6; z++)
            put(blocks, x, 0, z, block(Math.abs(x) == 3 || Math.abs(z) == 6 ? "create:brass_casing" : "minecraft:spruce_planks"));
        for (int x : new int[]{-3, 3}) for (int z : new int[]{-4, 4}) {
            for (int y = 1; y <= 6; y++) put(blocks, x, y, z, girder("y"));
            for (int xx = -3; xx <= 3; xx++) put(blocks, xx, 6, z, block("create:andesite_casing"));
        }
        for (int z = -4; z <= 4; z++) for (int x : new int[]{-3, 3})
            put(blocks, x, 6, z, girder("z"));
        // Twin exposed drive trains, including large wheels and shafts with native BE renderers.
        for (int x : new int[]{-5, 5}) {
            for (int z = -4; z <= 4; z++) {
                put(blocks, x, 1, z, block("create:copper_casing"));
                put(blocks, x, 3, z, property(block("create:shaft"), "axis", "z"));
            }
            for (int z : new int[]{-4, 0, 4}) {
                put(blocks, x, 3, z, property(block("create:large_cogwheel"), "axis", "z"));
                put(blocks, x, 4, z + 1, property(block("create:cogwheel"), "axis", "z"));
            }
            for (int z : new int[]{-5, 5}) {
                for (int y = 1; y <= 6; y++) put(blocks, x, y, z, property(block("create:shaft"), "axis", "y"));
                put(blocks, x, 6, z, property(block("create:mechanical_bearing"), "facing", "up"));
                // Four sail crosses give the rig an easily recognized silhouette from every side.
                for (int n = -2; n <= 2; n++) {
                    put(blocks, x + n, 7, z, block(n == 0 ? "create:brass_casing" : "create:white_sail"));
                    put(blocks, x, 7, z + n, block(n == 0 ? "create:brass_casing" : "create:white_sail"));
                }
            }
            for (int z = -4; z <= 4; z += 4) for (int xx = Math.min(x, -3); xx <= Math.max(x, 3); xx++)
                if (Math.signum(xx) == Math.signum(x) && Math.abs(xx) >= 3) put(blocks, xx, 1, z, girder("x"));
        }
        // Glazed cabin, a visible central flywheel train, and a row of downward deployers.
        for (int x = -1; x <= 1; x++) for (int z = 2; z <= 5; z++) {
            put(blocks, x, 4, z, block("create:copper_casing"));
            for (int y = 1; y <= 3; y++) if (Math.abs(x) == 1 || z == 5) put(blocks, x, y, z, block("minecraft:glass"));
        }
        for (int z = -4; z <= 1; z++) {
            put(blocks, 0, 3, z, property(block("create:shaft"), "axis", "z"));
            put(blocks, 0, 1, z, block("create:andesite_casing"));
        }
        for (int x = -2; x <= 2; x++) {
            put(blocks, x, 2, -6, property(block("create:mechanical_drill"), "facing", "north"));
            put(blocks, x, 1, -6, property(block("create:mechanical_drill"), "facing", "north"));
        }
        for (int x : new int[]{-2, 2}) for (int z : new int[]{-3, 0, 3})
            put(blocks, x, -1, z, property(block("create:deployer"), "facing", "down"));
        for (int x : new int[]{-2, 2}) for (int z = -3; z <= 2; z++)
            put(blocks, x, 2, z, property(property(block("create:fluid_pipe"), "north", "true"), "south", "true"));
        for (int x : new int[]{-2, 2}) put(blocks, x, 3, 0, property(block("create:mechanical_pump"), "facing", "north"));

        var contraption = new MountedContraption();
        contraption.anchor = BlockPos.ZERO; contraption.bounds = new AABB(-8, -1, -8, 9, 9, 9);
        contraption.getBlocks().putAll(blocks);
        for (var info : blocks.values()) {
            String id = BuiltInRegistries.BLOCK.getKey(info.state().getBlock()).getPath();
            if (!List.of("mechanical_drill", "deployer").contains(id)) continue;
            var actor = new MovementContext(level, info, contraption);
            actor.motion = new Vec3(0, 0, -.15); actor.relativeMotion = actor.motion;
            contraption.getActors().add(MutablePair.of(info, actor));
        }
        var entity = OrientedContraptionEntity.create(level, contraption, Direction.SOUTH);
        contraption.entity = entity; entity.setPos(0, 108, 0);
        return CreateModelSources.contraption(entity);
    }
    private static BlockState block(String name) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(name)).orElseThrow(() -> new IllegalArgumentException("Missing demo block " + name)).defaultBlockState();
    }
    private static BlockState girder(String axis) {
        var state = property(block("create:metal_girder"), "axis", axis);
        state = property(property(state, "top", "true"), "bottom", "true");
        if (!axis.equals("y")) state = property(state, axis, "true");
        return state;
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static BlockState property(BlockState state, String name, String value) {
        Property property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) throw new IllegalArgumentException("Missing demo property " + name + " in " + state);
        return state.setValue(property, (Comparable) property.getValue(value).orElseThrow());
    }
    private static void put(LinkedHashMap<BlockPos, StructureBlockInfo> blocks, int x, int y, int z, BlockState state) {
        var pos = new BlockPos(x, y, z); var data = new CompoundTag();
        data.putFloat("Speed", x < 0 ? -48 : 48);
        blocks.put(pos, new StructureBlockInfo(pos, state, data));
    }
}
