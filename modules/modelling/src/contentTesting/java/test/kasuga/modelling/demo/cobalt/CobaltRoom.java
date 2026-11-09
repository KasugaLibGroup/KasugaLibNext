package test.kasuga.modelling.demo.cobalt;

import lib.kasuga.scene.MinecraftStage;
import lib.kasuga.scene.SceneProps;
import lib.kasuga.scene.SceneWarmup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Authored room layout and choreography, composed from stage edits and vanilla-prop handles. */
final class CobaltRoom {
    // Keep the initial enclosure deliberately oversized: the player should
    // feel suspended in a white volume before the walls unfold into the sea.
    static final int MIN_X = -16, MAX_X = 16, MIN_Z = -20, MAX_Z = 20;
    static final int FLOOR_Y = 64, BED_Y = FLOOR_Y - 1, CEILING_Y = 78;
    static final BlockPos JUKEBOX = new BlockPos(0, 65, 0);
    static final Vec3 SOURCE = new Vec3(.5, 65.7, .5);
    static final Vec3 SLOT = new Vec3(.5, 66.43, .5);
    static final Vec3 DISC = new Vec3(-1.5, 66.31, .5);
    private final List<Panel> panels = new ArrayList<>();
    private final MinecraftStage stage;

    static void build(MinecraftStage stage) {
        CobaltCoast.buildGround(stage);
        stage.fill(new BlockPos(MIN_X - 1, FLOOR_Y + 1, MIN_Z - 1),
                new BlockPos(MAX_X + 1, CEILING_Y + 1, MAX_Z + 1), Blocks.AIR.defaultBlockState());
        stage.fill(new BlockPos(MIN_X, FLOOR_Y, MIN_Z), new BlockPos(MAX_X, FLOOR_Y, MAX_Z), Blocks.WHITE_CONCRETE.defaultBlockState());
        stage.fill(new BlockPos(MIN_X, FLOOR_Y + 1, MIN_Z), new BlockPos(MAX_X, CEILING_Y - 1, MIN_Z), Blocks.WHITE_CONCRETE.defaultBlockState());
        stage.fill(new BlockPos(MIN_X, FLOOR_Y + 1, MAX_Z), new BlockPos(MAX_X, CEILING_Y - 1, MAX_Z), Blocks.WHITE_CONCRETE.defaultBlockState());
        stage.fill(new BlockPos(MIN_X, FLOOR_Y + 1, MIN_Z + 1), new BlockPos(MIN_X, CEILING_Y - 1, MAX_Z - 1), Blocks.WHITE_CONCRETE.defaultBlockState());
        stage.fill(new BlockPos(MAX_X, FLOOR_Y + 1, MIN_Z + 1), new BlockPos(MAX_X, CEILING_Y - 1, MAX_Z - 1), Blocks.WHITE_CONCRETE.defaultBlockState());
        stage.fill(new BlockPos(MIN_X, CEILING_Y, MIN_Z), new BlockPos(MAX_X, CEILING_Y, MAX_Z), Blocks.WHITE_CONCRETE.defaultBlockState());
        for (int x = -14; x <= 14; x += 5) for (int z = -18; z <= 18; z += 6)
            stage.block(new BlockPos(x, CEILING_Y, z), Blocks.SEA_LANTERN.defaultBlockState());
        stage.block(JUKEBOX, Blocks.JUKEBOX.defaultBlockState());
        stage.block(new BlockPos(-2, 65, 0), Blocks.WHITE_CONCRETE.defaultBlockState());
    }

    static SceneWarmup warmup(ClientLevel level) {
        var warmup = SceneWarmup.forArea(level, new BlockPos(MIN_X, FLOOR_Y, MIN_Z),
                new BlockPos(MAX_X, CEILING_Y, MAX_Z));
        for (int x : new int[]{MIN_X, 0, MAX_X}) {
            for (int z : new int[]{MIN_Z, -4, 0, MAX_Z})
                warmup.requireBlock(new BlockPos(x, FLOOR_Y, z), Blocks.WHITE_CONCRETE.defaultBlockState());
        }
        return warmup.requireBlock(JUKEBOX, Blocks.JUKEBOX.defaultBlockState())
                .requireBlock(new BlockPos(0, CEILING_Y, -4), Blocks.WHITE_CONCRETE.defaultBlockState())
                .requireBlock(new BlockPos(MIN_X, FLOOR_Y + 2, 0), Blocks.WHITE_CONCRETE.defaultBlockState())
                .requireBlock(new BlockPos(MAX_X, FLOOR_Y + 2, 0), Blocks.WHITE_CONCRETE.defaultBlockState())
                .requireBlock(new BlockPos(0, FLOOR_Y + 2, MIN_Z), Blocks.WHITE_CONCRETE.defaultBlockState())
                .requireBlock(new BlockPos(0, FLOOR_Y + 2, MAX_Z), Blocks.WHITE_CONCRETE.defaultBlockState());
    }

    CobaltRoom(MinecraftStage stage, SceneProps props) {
        this.stage = stage;
        int order = 0;
        for (int x = MIN_X; x <= MAX_X; x += 4) {
            panel(props, new BlockPos(x, FLOOR_Y + 1, MIN_Z), new BlockPos(Math.min(x + 3, MAX_X), CEILING_Y - 1, MIN_Z), .8f + order++ * .13f, 0, -1, false);
            panel(props, new BlockPos(x, FLOOR_Y + 1, MAX_Z), new BlockPos(Math.min(x + 3, MAX_X), CEILING_Y - 1, MAX_Z), .8f + order++ * .13f, 0, 1, false);
        }
        for (int z = MIN_Z + 1; z <= MAX_Z - 1; z += 4) {
            panel(props, new BlockPos(MIN_X, FLOOR_Y + 1, z), new BlockPos(MIN_X, CEILING_Y - 1, Math.min(z + 3, MAX_Z - 1)), .8f + order++ * .13f, -1, 0, false);
            panel(props, new BlockPos(MAX_X, FLOOR_Y + 1, z), new BlockPos(MAX_X, CEILING_Y - 1, Math.min(z + 3, MAX_Z - 1)), .8f + order++ * .13f, 1, 0, false);
        }
        for (int z = MIN_Z; z <= MAX_Z; z += 6) for (int x = MIN_X; x <= MAX_X; x += 6)
            panel(props, new BlockPos(x, CEILING_Y, z), new BlockPos(Math.min(x + 5, MAX_X), CEILING_Y, Math.min(z + 5, MAX_Z)),
                    1.4f + (x + z + 13) * .04f, x > 0 ? 1 : -1, z > 0 ? 1 : -1, true);
        for (int z = MIN_Z; z <= MAX_Z; z += 3) for (int x = MIN_X; x <= MAX_X; x += 3) {
            panel(props, new BlockPos(x, FLOOR_Y, z), new BlockPos(Math.min(x + 2, MAX_X), FLOOR_Y, Math.min(z + 2, MAX_Z)),
                    4.7f + ((x * 17 + z * 29 + 1000) % 19) * .08f, x > 0 ? 1 : -1, z > 0 ? 1 : -1, true);
        }
        panel(props, new BlockPos(-2, 65, 0), new BlockPos(-2, 65, 0), 4.3f, -1, 0, true);
    }
    private void panel(SceneProps props, BlockPos min, BlockPos max, float delay, int outX, int outZ, boolean tile) {
        var center = new Vec3((min.getX() + max.getX() + 1) * .5, (min.getY() + max.getY() + 1) * .5,
                (min.getZ() + max.getZ() + 1) * .5);
        var visual = props.block(Blocks.WHITE_CONCRETE.defaultBlockState(), center)
                .scale(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1).visible(false);
        panels.add(new Panel(min, max, center, visual, delay, outX, outZ, tile));
    }
    void sample(float seconds) {
        for (var panel : panels) {
            float t = Math.clamp((seconds - panel.delay) / (panel.tile ? 3.8f : 5.5f), 0, 1);
            if (t <= 0) continue;
            if (!panel.started) {
                panel.started = true;
                stage.execute(world -> {
                    var floor = panel.min.getY() == FLOOR_Y && panel.max.getY() == FLOOR_Y;
                    if (floor) {
                        for (int x = panel.min.getX(); x <= panel.max.getX(); x++)
                            for (int z = panel.min.getZ(); z <= panel.max.getZ(); z++)
                                CobaltCoast.restoreColumn(world, x, z);
                    } else world.fill(panel.min, panel.max, Blocks.AIR.defaultBlockState());
                });
            }
            float eased = CobaltSequence.ease(t);
            float angle = eased * 110;
            double reach = panel.tile ? eased * 1.3 : Math.sin(Math.toRadians(angle)) * 2.8;
            double drop = panel.tile ? eased * eased * 14 : (1 - Math.cos(Math.toRadians(angle))) * 2.8 + t * t * 7;
            panel.visual.position(panel.center.add(panel.outX * reach, -drop, panel.outZ * reach))
                    .rotation(panel.outZ * angle, panel.tile ? angle * .3f : 0, -panel.outX * angle).visible(t < 1);
        }
    }

    private static final class Panel {
        final BlockPos min, max;
        final Vec3 center;
        final SceneProps.Prop visual;
        final float delay;
        final int outX, outZ;
        final boolean tile;
        boolean started;
        Panel(BlockPos min, BlockPos max, Vec3 center, SceneProps.Prop visual, float delay, int outX, int outZ, boolean tile) {
            this.min = min; this.max = max; this.center = center; this.visual = visual; this.delay = delay;
            this.outX = outX; this.outZ = outZ; this.tile = tile;
        }
    }
}
