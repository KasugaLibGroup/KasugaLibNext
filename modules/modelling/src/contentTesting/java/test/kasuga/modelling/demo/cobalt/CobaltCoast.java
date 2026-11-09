package test.kasuga.modelling.demo.cobalt;

import lib.kasuga.scene.MinecraftStage;
import lib.kasuga.scene.SceneGround;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Shared authored heightfield for the OBJ, temporary collision, and water contact. */
final class CobaltCoast {
    static final float SEA_LEVEL = 64.55f;
    static final float DEEP_SEABED = -32f;
    static final int MIN_X = -48, MAX_X = 48, MIN_Z = -36, MAX_Z = 240;
    static final int MAX_SURFACE_BLOCK_Y = 66;
    private static final float SHALLOW_SHELF_END = 18f;
    private static final float DEEP_SLOPE = .55f;

    private CobaltCoast() {}

    static float shoreZ(float x) {
        return 5.0f + 1.80f * (float) Math.sin(x * .21f)
                + .72f * (float) Math.sin(x * .57f + 1.1f)
                + .52f * (float) Math.sin(x * 1.31f - .4f);
    }

    static float height(float x, float z) {
        float delta = z - shoreZ(x);
        float ripple = .018f * (float) Math.sin(x * .43f + z * .31f)
                + .012f * (float) Math.sin(x * 1.7f - z * .19f);
        if (delta <= 0) {
            float dune = smoothstep((-delta - 8) / 28)
                    * (1.05f * (.72f + .28f * (float) Math.sin(x * .19f + .8f)));
            return 64.98f + dune + ripple;
        }
        float shallowFloor = 64.98f - .88f * smoothstep(delta / 3.2f)
                - .55f * smoothstep((delta - 3.2f) / 7.0f);
        if (delta <= 10.2f) return shallowFloor + ripple;
        if (delta <= SHALLOW_SHELF_END) return 63.55f + ripple;

        float descent = delta - SHALLOW_SHELF_END;
        float drop = descent <= 4f
                ? DEEP_SLOPE * 4f * (cube(descent / 4f) - .5f * square(descent / 4f) * square(descent / 4f))
                : DEEP_SLOPE * (descent - 2f);
        return Math.max(DEEP_SEABED, 63.55f - drop) + ripple;
    }

    static float waterlineZ(float x) {
        float low = shoreZ(x), high = low + 16;
        for (int i = 0; i < 32; i++) {
            float middle = (low + high) * .5f;
            if (height(x, middle) > SEA_LEVEL) low = middle;
            else high = middle;
        }
        return (low + high) * .5f;
    }

    static float collisionHeight(float x, float z) {
        return Math.round(height(x, z) * 16) / 16f;
    }

    static int topBlockY(float x, float z) {
        return Math.floorDiv(Math.round(collisionHeight(x, z) * 16) - 1, 16);
    }

    static int topLayers(float x, float z) {
        int units = Math.round(collisionHeight(x, z) * 16);
        int block = Math.floorDiv(units - 1, 16);
        return units - block * 16;
    }

    static void buildGround(MinecraftStage stage) {
        for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++) {
            if (inRoom(x, z)) continue;
            placeColumn(stage, x, z);
        }
    }

    static void restoreColumn(MinecraftStage stage, int x, int z) {
        for (int y = CobaltRoom.FLOOR_Y; y <= MAX_SURFACE_BLOCK_Y + 1; y++)
            stage.block(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        placeColumn(stage, x, z);
    }

    private static void placeColumn(MinecraftStage stage, int x, int z) {
        int topY = topBlockY(x + .5f, z + .5f);
        stage.block(new BlockPos(x, topY, z), SceneGround.stateForLayers(topLayers(x + .5f, z + .5f)));
    }

    private static boolean inRoom(int x, int z) {
        return x >= CobaltRoom.MIN_X && x <= CobaltRoom.MAX_X
                && z >= CobaltRoom.MIN_Z && z <= CobaltRoom.MAX_Z;
    }

    private static float smoothstep(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float square(float value) { return value * value; }
    private static float cube(float value) { return value * value * value; }
}
