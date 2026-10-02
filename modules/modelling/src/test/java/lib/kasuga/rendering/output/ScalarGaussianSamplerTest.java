package lib.kasuga.rendering.output;
import lib.kasuga.rendering.output.mc.ScalarGaussianSampler;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
class ScalarGaussianSamplerTest {
    @Test void bitExactAgainstVanillaAcrossNegativeCoordinatesAndFractionalWeights() {
        var random = new Random(43827);
        for (int i = 0; i < 200; i++) {
            var point = new Vec3(random.nextDouble() * 2048 - 1024, random.nextDouble() * 1024 - 512, random.nextDouble() * 2048 - 1024);
            var originalOrder = new ArrayList<String>(); var optimizedOrder = new ArrayList<String>();
            var original = CubicSampler.gaussianSampleVec3(point, (x, y, z) -> {
                originalOrder.add(x + ":" + y + ":" + z); return color(x, y, z);
            });
            var optimized = ScalarGaussianSampler.sample(point, (x, y, z) -> {
                optimizedOrder.add(x + ":" + y + ":" + z); return color(x, y, z);
            });
            assertEquals(Double.doubleToRawLongBits(original.x), Double.doubleToRawLongBits(optimized.x));
            assertEquals(Double.doubleToRawLongBits(original.y), Double.doubleToRawLongBits(optimized.y));
            assertEquals(Double.doubleToRawLongBits(original.z), Double.doubleToRawLongBits(optimized.z));
            assertEquals(216, originalOrder.size()); assertEquals(originalOrder, optimizedOrder);
        }
    }
    private static Vec3 color(int x, int y, int z) { return new Vec3(Math.sin(x * 0.7), Math.cos(y * 0.3), Math.sin(z * 0.9)); }

    @Test void packedRgbPreservesVanillaBitsAndFetchOrder() {
        var random = new Random(91732);
        for (int i = 0; i < 200; i++) {
            var point = new Vec3(random.nextDouble() * 2048 - 1024, random.nextDouble() * 1024 - 512, random.nextDouble() * 2048 - 1024);
            var originalOrder = new ArrayList<String>(); var optimizedOrder = new ArrayList<String>();
            var original = CubicSampler.gaussianSampleVec3(point, (x, y, z) -> {
                originalOrder.add(x + ":" + y + ":" + z);
                return Vec3.fromRGB24(rgb(x, y, z));
            });
            var optimized = ScalarGaussianSampler.sampleRgb(point, (x, y, z) -> {
                optimizedOrder.add(x + ":" + y + ":" + z);
                return rgb(x, y, z);
            });
            assertEquals(Double.doubleToRawLongBits(original.x), Double.doubleToRawLongBits(optimized.x));
            assertEquals(Double.doubleToRawLongBits(original.y), Double.doubleToRawLongBits(optimized.y));
            assertEquals(Double.doubleToRawLongBits(original.z), Double.doubleToRawLongBits(optimized.z));
            assertEquals(216, originalOrder.size()); assertEquals(originalOrder, optimizedOrder);
        }
    }

    private static int rgb(int x, int y, int z) { return (x * 73856093) ^ (y * 19349663) ^ (z * 83492791); }
}
