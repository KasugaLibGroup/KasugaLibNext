package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reference for ping-pong targets whose clears are skipped by GPU predication. */
class ConditionalPeelClearTest {
    private record Fragment(double depth, double color, double alpha) {}
    private static final class Target {
        double depth = 1, color, alpha;
        void clear(double z) { depth = z; color = alpha = 0; }
    }
    private static final class Renderer {
        Target previous = new Target(), current = new Target();
        int clears;

        double frame(List<Fragment> fragments, int batchSize) {
            previous.depth = 0; // Previous color deliberately retains stale data.
            double color = 0, alpha = 0;
            boolean batchVisible = true;
            clears = 0;
            for (int layer = 0; layer < 32; layer++) {
                boolean enabled = layer < batchSize || batchVisible;
                Fragment nearest = null;
                if (enabled) {
                    current.clear(1);
                    clears++;
                    for (Fragment f : fragments) {
                        if (alpha >= 1 || f.alpha <= 1.0 / 255 || f.depth <= previous.depth || f.depth >= 1) continue;
                        if (nearest == null || f.depth < nearest.depth) nearest = f;
                    }
                    if (nearest != null) {
                        current.depth = nearest.depth;
                        current.color = nearest.color * nearest.alpha;
                        current.alpha = nearest.alpha;
                    }
                    color += (1 - alpha) * current.color;
                    alpha += (1 - alpha) * current.alpha;
                }
                if ((layer + 1) % batchSize == 0 || layer == 31) batchVisible = nearest != null;
                Target swap = previous; previous = current; current = swap;
            }
            return color + (1 - alpha) * .6;
        }
    }

    @Test void skipsTailClearsWithoutRecompositingStaleColor() {
        var renderer = new Renderer();
        List<Fragment> scene = List.of(new Fragment(.2, .2, .5), new Fragment(.4, .8, .5));
        assertEquals(expected(scene), renderer.frame(scene, 4), 1e-12);
        assertEquals(4, renderer.clears);
    }

    @Test void batchBoundaryAndLayerCapAreUnchanged() {
        var renderer = new Renderer();
        for (int count : new int[]{0, 1, 3, 4, 5, 16, 31, 32, 40}) {
            List<Fragment> scene = new ArrayList<>();
            for (int i = 0; i < count; i++) scene.add(new Fragment((i + 1) / 50.0, i / 50.0, .05));
            for (int batch : new int[]{1, 3, 4, 16}) {
                assertEquals(expected(scene), renderer.frame(scene, batch), 1e-12);
            }
        }
    }

    @Test void movingAndDisappearingGeometryCannotLeakPreviousFrame() {
        var renderer = new Renderer();
        var random = new Random(1926);
        for (int frame = 0; frame < 100; frame++) {
            List<Fragment> scene = new ArrayList<>();
            int count = random.nextInt(12);
            for (int i = 0; i < count; i++) scene.add(new Fragment(random.nextDouble(), random.nextDouble(), .5));
            assertEquals(expected(scene), renderer.frame(scene, 4), 1e-12);
            assertEquals(.6, renderer.frame(List.of(), 4), 1e-12);
        }
    }

    private static double expected(List<Fragment> scene) {
        double result = .6;
        for (Fragment f : scene.stream().sorted(Comparator.comparingDouble(Fragment::depth)).limit(32).toList().reversed()) {
            result = f.color * f.alpha + result * (1 - f.alpha);
        }
        return result;
    }
}
