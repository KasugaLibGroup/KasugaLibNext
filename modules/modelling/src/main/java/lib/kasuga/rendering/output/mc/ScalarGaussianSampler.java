package lib.kasuga.rendering.output.mc;

import net.minecraft.util.CubicSampler;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Same kernel, fetch order and floating-point operations as MC, without two temporary vectors per sample. */
public final class ScalarGaussianSampler {
    private static final double[] KERNEL = {0, 1, 4, 6, 4, 1, 0};
    private ScalarGaussianSampler() {}
    @FunctionalInterface
    public interface RgbFetcher {
        int fetch(int x, int y, int z);
    }

    /** Packed biome sky RGB; preserves vanilla's division and accumulation order. */
    public static Vec3 sampleRgb(Vec3 point, RgbFetcher fetcher) {
        int x = Mth.floor(point.x), y = Mth.floor(point.y), z = Mth.floor(point.z);
        double fx = point.x - x, fy = point.y - y, fz = point.z - z;
        double weightSum = 0, red = 0, green = 0, blue = 0;
        for (int dx = 0; dx < 6; dx++) {
            double wx = Mth.lerp(fx, KERNEL[dx + 1], KERNEL[dx]);
            for (int dy = 0; dy < 6; dy++) {
                double wy = Mth.lerp(fy, KERNEL[dy + 1], KERNEL[dy]);
                for (int dz = 0; dz < 6; dz++) {
                    double wz = Mth.lerp(fz, KERNEL[dz + 1], KERNEL[dz]);
                    double weight = wx * wy * wz;
                    weightSum += weight;
                    int rgb = fetcher.fetch(x - 2 + dx, y - 2 + dy, z - 2 + dz);
                    red += ((rgb >> 16 & 255) / 255.0) * weight;
                    green += ((rgb >> 8 & 255) / 255.0) * weight;
                    blue += ((rgb & 255) / 255.0) * weight;
                }
            }
        }
        double inverse = 1.0 / weightSum;
        return new Vec3(red * inverse, green * inverse, blue * inverse);
    }

    public static Vec3 sample(Vec3 point, CubicSampler.Vec3Fetcher fetcher) {
        int x = Mth.floor(point.x), y = Mth.floor(point.y), z = Mth.floor(point.z);
        double fx = point.x - x, fy = point.y - y, fz = point.z - z;
        double weightSum = 0, red = 0, green = 0, blue = 0;
        for (int dx = 0; dx < 6; dx++) {
            double wx = Mth.lerp(fx, KERNEL[dx + 1], KERNEL[dx]);
            for (int dy = 0; dy < 6; dy++) {
                double wy = Mth.lerp(fy, KERNEL[dy + 1], KERNEL[dy]);
                for (int dz = 0; dz < 6; dz++) {
                    double wz = Mth.lerp(fz, KERNEL[dz + 1], KERNEL[dz]);
                    double weight = wx * wy * wz;
                    weightSum += weight;
                    Vec3 value = fetcher.fetch(x - 2 + dx, y - 2 + dy, z - 2 + dz);
                    red += value.x * weight;
                    green += value.y * weight;
                    blue += value.z * weight;
                }
            }
        }
        double inverse = 1.0 / weightSum;
        return new Vec3(red * inverse, green * inverse, blue * inverse);
    }
}
