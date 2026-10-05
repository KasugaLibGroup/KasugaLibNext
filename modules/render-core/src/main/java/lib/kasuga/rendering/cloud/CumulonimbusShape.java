package lib.kasuga.rendering.cloud;

import java.util.List;

/** Billowing base/towers and a spreading anvil, in x/z [-1,1], y [0,1]. Shared by CPU and GPU. */
public final class CumulonimbusShape {
    public static final List<Lobe> LOBES = List.of(
            new Lobe(-.12f, .14f, 0, .59f, .22f, .56f, false),
            new Lobe(-.40f, .20f, .08f, .30f, .23f, .36f, false),
            new Lobe(.20f, .21f, -.08f, .32f, .25f, .38f, false),
            new Lobe(-.16f, .39f, 0, .46f, .29f, .43f, false),
            new Lobe(-.40f, .43f, .08f, .27f, .23f, .30f, false),
            new Lobe(.10f, .46f, -.06f, .28f, .27f, .31f, false),
            new Lobe(-.19f, .65f, .02f, .43f, .28f, .39f, false),
            new Lobe(-.44f, .66f, .06f, .24f, .18f, .26f, false),
            new Lobe(.09f, .71f, -.08f, .30f, .22f, .32f, false),
            new Lobe(-.14f, .82f, 0, .54f, .17f, .46f, true),
            new Lobe(.35f, .86f, .02f, .60f, .13f, .43f, true),
            new Lobe(.68f, .88f, .03f, .28f, .065f, .28f, true),
            new Lobe(-.39f, .31f, -.18f, .19f, .17f, .22f, false),
            new Lobe(.18f, .30f, .20f, .19f, .16f, .22f, false),
            new Lobe(-.39f, .52f, -.24f, .27f, .22f, .27f, false),
            new Lobe(.15f, .56f, .20f, .25f, .20f, .26f, false),
            new Lobe(-.37f, .76f, -.10f, .26f, .19f, .27f, false),
            new Lobe(-.06f, .80f, .22f, .30f, .19f, .26f, false),
            new Lobe(-.48f, .82f, .02f, .22f, .11f, .30f, true),
            new Lobe(.12f, .90f, .17f, .32f, .08f, .28f, true),
            new Lobe(.51f, .90f, -.12f, .31f, .065f, .24f, true),
            new Lobe(-.03f, .14f, .35f, .30f, .16f, .28f, false),
            new Lobe(-.36f, .18f, -.30f, .25f, .19f, .26f, false),
            new Lobe(.26f, .17f, .08f, .28f, .18f, .29f, false));

    private CumulonimbusShape() {}

    /** Bake tower/anvil envelopes once, off the render thread, instead of evaluating every lobe per ray sample. */
    public static float[] bake(int size) {
        if (size < 4 || size > 128) throw new IllegalArgumentException("Shape size must be 4..128");
        float[] grid = new float[size * size * size * 2];
        for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float px = (x + .5f) / size * 2 - 1, py = (y + .5f) / size, pz = (z + .5f) / size * 2 - 1;
            int offset = ((z * size + y) * size + x) * 2;
            for (var lobe : LOBES) {
                float dx = (px - lobe.x) / lobe.rx, dy = (py - lobe.y) / lobe.ry, dz = (pz - lobe.z) / lobe.rz;
                float interior = Math.max(0, 1 - (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
                int channel = lobe.anvil ? 1 : 0;
                grid[offset + channel] = Math.max(grid[offset + channel], interior);
            }
        }
        return grid;
    }

    /** Signed interior distance proxy; positive inside. The GPU adds bounded turbulent erosion. */
    public static float envelope(float x, float y, float z, float anvil) {
        if (Math.abs(x) >= 1 || y <= 0 || y >= 1 || Math.abs(z) >= 1) return 0;
        float interior = 0;
        for (Lobe lobe : LOBES) {
            if (lobe.anvil && anvil == 0) continue;
            double dx = (x - lobe.x) / lobe.rx, dy = (y - lobe.y) / lobe.ry, dz = (z - lobe.z) / lobe.rz;
            float d = (float) (1 - Math.sqrt(dx * dx + dy * dy + dz * dz));
            interior = Math.max(interior, d * (lobe.anvil ? anvil : 1));
        }
        return interior;
    }

    public record Lobe(float x, float y, float z, float rx, float ry, float rz, boolean anvil) {}
}
