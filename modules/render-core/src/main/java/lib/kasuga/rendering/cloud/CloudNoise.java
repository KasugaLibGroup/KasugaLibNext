package lib.kasuga.rendering.cloud;

/** Periodic RGBA noise: Perlin-Worley, low Worley fBm, high Worley fBm, and Perlin turbulence. */
public final class CloudNoise {
    private final int size;
    private final byte[] pixels;
    private CloudNoise(int size, byte[] pixels) { this.size = size; this.pixels = pixels; }
    public int size() { return size; }
    public byte[] pixels() { return pixels.clone(); }

    public static CloudNoise generate(int size) {
        if (size < 4 || size > 128) throw new IllegalArgumentException("Noise size must be 4..128");
        byte[] pixels = new byte[size * size * size * 4];
        for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double u = (x + .5) / size, v = (y + .5) / size, w = (z + .5) / size;
            double fbm = perlin(u, v, w, 4) * .57 + perlin(u, v, w, 8) * .29 + perlin(u, v, w, 16) * .14;
            double w4 = worley(u, v, w, 4), w8 = worley(u, v, w, 8), w16 = worley(u, v, w, 16), w32 = worley(u, v, w, 32);
            double low = w4 * .625 + w8 * .25 + w16 * .125;
            double high = w8 * .625 + w16 * .25 + w32 * .125;
            // Remapping erodes low-density regions while retaining a connected dense core.
            double perlinWorley = Math.clamp((fbm - (1 - low) * .65) / Math.max(low * .65 + .35, .001), 0, 1);
            int index = ((z * size + y) * size + x) * 4;
            pixels[index] = quantize(perlinWorley); pixels[index + 1] = quantize(low);
            pixels[index + 2] = quantize(high); pixels[index + 3] = quantize(fbm);
        }
        return new CloudNoise(size, pixels);
    }

    private static byte quantize(double value) { return (byte) Math.round(Math.clamp(value, 0, 1) * 255); }

    private static double perlin(double u, double v, double w, int period) {
        double x = u * period, y = v * period, z = w * period;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double sx = fade(fx), sy = fade(fy), sz = fade(fz), result = 0;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int h = (int) (hash(ix + dx, iy + dy, iz + dz, period, 71) * 16) & 15;
            double px = fx - dx, py = fy - dy, pz = fz - dz;
            double a = h < 8 ? px : py, b = h < 4 ? py : h == 12 || h == 14 ? px : pz;
            double gradient = ((h & 1) == 0 ? a : -a) + ((h & 2) == 0 ? b : -b);
            result += gradient * (dx == 0 ? 1 - sx : sx) * (dy == 0 ? 1 - sy : sy) * (dz == 0 ? 1 - sz : sz);
        }
        return Math.clamp(.5 + result * .7, 0, 1);
    }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    public static double value(double u, double v, double w, int period) {
        double x = u * period, y = v * period, z = w * period;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz), result = 0;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
            result += hash(ix + dx, iy + dy, iz + dz, period, 0)
                    * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
        return result;
    }

    private static double worley(double u, double v, double w, int period) {
        double x = u * period, y = v * period, z = w * period;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double minimum = 3;
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int cx = ix + dx, cy = iy + dy, cz = iz + dz;
            double px = cx + hash(cx, cy, cz, period, 11) - x;
            double py = cy + hash(cx, cy, cz, period, 23) - y;
            double pz = cz + hash(cx, cy, cz, period, 37) - z;
            minimum = Math.min(minimum, px * px + py * py + pz * pz);
        }
        return Math.clamp(1 - Math.sqrt(minimum), 0, 1);
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
    private static double hash(int x, int y, int z, int period, int salt) {
        int h = Math.floorMod(x, period) * 374761393 + Math.floorMod(y, period) * 668265263
                + Math.floorMod(z, period) * 2147483647 + salt * 1274126177;
        h = (h ^ h >>> 13) * 1274126177;
        return Integer.toUnsignedLong(h ^ h >>> 16) / 4294967295.0;
    }
}
