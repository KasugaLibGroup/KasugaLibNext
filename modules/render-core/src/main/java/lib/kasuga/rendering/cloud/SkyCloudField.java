package lib.kasuga.rendering.cloud;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Infinite deterministic weather cells, with a bounded window for each observing camera.
 * Only ticks advance time; sampling additional cameras cannot change the weather or wind. */
public final class SkyCloudField {
    public static final int GRID_SIZE = 32;
    public static final int MAX_LAYERS = 4;
    private final LinkedHashMap<Key, Window> windows = new LinkedHashMap<>(8, .75f, true);
    private SkyCloudSettings settings;
    private double previousTime, time, previousWindX, previousWindZ, windX, windZ;

    public SkyCloudField(SkyCloudSettings settings) { this.settings = Objects.requireNonNull(settings); }
    public synchronized SkyCloudSettings settings() { return settings; }
    public synchronized void settings(SkyCloudSettings value) {
        Objects.requireNonNull(value);
        if (settings.seed() != value.seed()) windows.clear();
        settings = value;
    }
    public synchronized void tick(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0 || !Double.isFinite(time + seconds))
            throw new IllegalArgumentException("Cloud tick must be finite and nonnegative");
        double nextX = windX + settings.windX() * seconds, nextZ = windZ + settings.windZ() * seconds;
        if (!Double.isFinite(nextX) || !Double.isFinite(nextZ)) throw new IllegalArgumentException("Cloud wind outside supported range");
        previousTime = time; time += seconds;
        previousWindX = windX; previousWindZ = windZ; windX = nextX; windZ = nextZ;
    }
    public synchronized int cachedWindowCount() { return windows.size(); }
    public synchronized Frame sample(double cameraX, double cameraY, double cameraZ, float partialTick, float rain) {
        for (double v : new double[]{cameraX, cameraY, cameraZ, partialTick, rain})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Cloud observation must be finite");
        // Keep subtraction precise, including near the Minecraft world border.
        double alpha = Math.clamp(partialTick, 0, 1);
        double t = previousTime + (time - previousTime) * alpha;
        double windX = previousWindX + (this.windX - previousWindX) * alpha;
        double windZ = previousWindZ + (this.windZ - previousWindZ) * alpha;
        long cellX = cell((cameraX - windX) / settings.cellSize()), cellZ = cell((cameraZ - windZ) / settings.cellSize());
        var key = new Key(cellX - GRID_SIZE / 2, cellZ - GRID_SIZE / 2, settings.seed());
        var window = windows.computeIfAbsent(key, SkyCloudField::generate);
        if (windows.size() > 8) { var iterator = windows.keySet().iterator(); iterator.next(); iterator.remove(); }
        float coverage = Math.min(1, settings.coverage() + Math.clamp(rain, 0, 1) * .25f);
        return new Frame(settings, window, t, (float) (key.x * (double) settings.cellSize() + windX - cameraX),
                (float) (settings.baseHeight() - cameraY), (float) (key.z * (double) settings.cellSize() + windZ - cameraZ), coverage);
    }
    private static long cell(double value) {
        if (Math.abs(value) > 1e12) throw new IllegalArgumentException("Cloud observer outside supported world range");
        return (long) Math.floor(value);
    }
    private static Window generate(Key key) {
        float[] layout = new float[GRID_SIZE * GRID_SIZE * 4], dimensions = new float[layout.length];
        for (int z = 0; z < GRID_SIZE; z++) for (int x = 0; x < GRID_SIZE; x++) {
            long random = mix((key.x + x) * 0x9e3779b97f4a7c15L ^ (key.z + z) * 0xc2b2ae3d27d4eb4fL ^ key.seed);
            int i = (z * GRID_SIZE + x) * 4;
            layout[i] = (unit(random) - .5f) * .05f; random = mix(random);
            layout[i + 1] = (unit(random) - .5f) * .05f; random = mix(random);
            double worldX = key.x + x, worldZ = key.z + z;
            float moisture = weather(worldX / 3.0 + 17.31, worldZ / 3.0 - 9.73, key.seed) * .7f
                    + weather(worldX / 9.0 - 11.17, worldZ / 9.0 + 21.59, key.seed + 71) * .3f;
            float type = unit(random); int kind = type < .025f && moisture > .48f ? 1 : type < .18f ? 2 : 0;
            random = mix(random);
            // Correlated weather creates cloud banks and large clear regions, not uniform occupancy.
            layout[i + 3] = Math.clamp(.5f + (.5f - moisture) * 2 + (unit(random) - .5f) * .30f, .015f, .985f);
            random = mix(random);
            layout[i + 2] = kind + .025f + weather(worldX / 9, worldZ / 9, key.seed + 113) * .06f + unit(random) * .012f;
            random = mix(random);
            float size = kind == 1 ? .85f + unit(random) * .12f : .18f + (float) Math.pow(unit(random), .85) * .78f;
            dimensions[i] = size; random = mix(random);
            dimensions[i + 2] = size * (.65f + unit(random) * .32f); random = mix(random);
            dimensions[i + 1] = kind == 1 ? .72f + unit(random) * .16f : kind == 2
                    ? size * (.10f + unit(random) * .12f) : size * (.17f + unit(random) * .45f);
            random = mix(random); dimensions[i + 3] = unit(random) * (float) (Math.PI * 2);
            float cosine = Math.abs((float) Math.cos(dimensions[i + 3])), sine = Math.abs((float) Math.sin(dimensions[i + 3]));
            float fit = Math.min(1, .94f / Math.max(cosine * dimensions[i] + sine * dimensions[i + 2],
                    sine * dimensions[i] + cosine * dimensions[i + 2]));
            dimensions[i] *= fit; dimensions[i + 2] *= fit;
            // Storm envelopes stay within their weather cell; continuous layers have no such boundary.
            layout[i] *= (float) ((.49 - (cosine * dimensions[i] + sine * dimensions[i + 2]) * .5) / .025);
            layout[i + 1] *= (float) ((.49 - (sine * dimensions[i] + cosine * dimensions[i + 2]) * .5) / .025);
        }
        float[] weatherLayers = new float[GRID_SIZE * GRID_SIZE * MAX_LAYERS * 4];
        for (int layer = 0; layer < MAX_LAYERS; layer++) for (int z = 0; z < GRID_SIZE; z++) for (int x = 0; x < GRID_SIZE; x++) {
            double wx = key.x + x + .5, wz = key.z + z + .5;
            int i = ((layer * GRID_SIZE + z) * GRID_SIZE + x) * 4, seed = key.seed + layer * 977;
            // Smooth controls, independent per layer. Bodies may span any number of weather cells.
            weatherLayers[i] = weather(wx / 3 + 17.31, wz / 3 - 9.73, seed) * .65f
                    + weather(wx / 9 - 11.17, wz / 9 + 21.59, seed + 71) * .35f;
            weatherLayers[i + 1] = weather(wx / 2.5 + 7.19, wz / 2.5 - 3.41, seed + 151);
            weatherLayers[i + 2] = weather(wx / 7, wz / 7, seed + 113);
            weatherLayers[i + 3] = weather(wx / 4 - 13.7, wz / 4 + 19.3, seed + 227);
        }
        return new Window(key, layout, dimensions, weatherLayers);
    }
    private static float weather(double x, double z, int seed) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        float fx = (float) (x - ix), fz = (float) (z - iz);
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        float a = weatherCorner(ix, iz, seed), b = weatherCorner(ix + 1, iz, seed);
        float c = weatherCorner(ix, iz + 1, seed), d = weatherCorner(ix + 1, iz + 1, seed);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }
    private static float weatherCorner(long x, long z, int seed) {
        return unit(mix(x * 0x9e3779b97f4a7c15L ^ z * 0xc2b2ae3d27d4eb4fL ^ seed));
    }
    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
    private static float unit(long value) { return (value >>> 40) * 0x1.0p-24f; }
    public record Key(long x, long z, int seed) {}
    public static final class Window {
        private final Key key;
        private final float[] layout, dimensions, weatherLayers;
        private Window(Key key, float[] layout, float[] dimensions, float[] weatherLayers) {
            this.key = key; this.layout = layout; this.dimensions = dimensions; this.weatherLayers = weatherLayers;
        }
        public Key key() { return key; }
        /** Upload once per window, never once per frame. */
        public float[] layout() { return layout.clone(); }
        public float[] dimensions() { return dimensions.clone(); }
        public float[] weatherLayers() { return weatherLayers.clone(); }
        public float weather(int layer, int x, int z, int channel) {
            return weatherLayers[((layer * GRID_SIZE + z) * GRID_SIZE + x) * 4 + channel];
        }
        public float value(int x, int z, int channel) { return layout[(z * GRID_SIZE + x) * 4 + channel]; }
        /** Diagnostic weather-cell occupancy, not an enumeration of continuous cloud bodies. */
        public int weatherCellCount(float coverage) {
            int count = 0;
            for (int i = 3; i < layout.length; i += 4) if (layout[i] < coverage) count++;
            return count;
        }
    }
    public record Frame(SkyCloudSettings settings, Window window, double timeSeconds,
                        float originX, float baseY, float originZ, float coverage) {
        public int weatherCellCount() { return window.weatherCellCount(coverage); }
    }
}
