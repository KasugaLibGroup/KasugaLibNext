package lib.kasuga.rendering.cloud;

/** RG: cumulonimbus tower/anvil. B: cumulus billows. A: broad stratocumulus. */
public final class CloudShapes {
    private CloudShapes() {}
    public static float[] bake(int size) {
        float[] storm = CumulonimbusShape.bake(size), result = new float[size * size * size * 4];
        float[][] puffs = {{0, .27f, 0, .64f, .31f, .60f}, {-.40f, .30f, .04f, .35f, .30f, .37f},
                {.34f, .32f, .14f, .40f, .34f, .42f}, {-.10f, .56f, -.05f, .44f, .37f, .44f},
                {.19f, .64f, -.08f, .29f, .28f, .31f}, {-.31f, .49f, -.22f, .32f, .30f, .35f},
                {-.08f, .31f, .36f, .40f, .28f, .33f}};
        float[] flat = {0, .44f, 0, .91f, .42f, .84f};
        for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int voxel = (z * size + y) * size + x, i = voxel * 4;
            result[i] = storm[voxel * 2]; result[i + 1] = storm[voxel * 2 + 1];
            float px = (x + .5f) / size * 2 - 1, py = (y + .5f) / size, pz = (z + .5f) / size * 2 - 1;
            for (float[] p : puffs) result[i + 2] = Math.max(result[i + 2], ellipsoid(px, py, pz, p));
            result[i + 3] = Math.max(ellipsoid(px, py, pz, flat), result[i + 2] * .8f);
        }
        return result;
    }
    private static float ellipsoid(float x, float y, float z, float[] p) {
        float dx = (x - p[0]) / p[3], dy = (y - p[1]) / p[4], dz = (z - p[2]) / p[5];
        return Math.max(0, 1 - (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
