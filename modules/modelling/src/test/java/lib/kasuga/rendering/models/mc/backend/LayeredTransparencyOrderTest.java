package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reference pixels for the four model/water/glass acceptance arrangements. */
class LayeredTransparencyOrderTest {
    private record Fragment(double depth, double color, double alpha) {}

    @Test void waterInFrontOfModel() {
        check(List.of(new Fragment(.2, .1, .5), new Fragment(.4, .9, .5)), .425);
    }

    @Test void modelInFrontOfWater() {
        check(List.of(new Fragment(.4, .1, .5), new Fragment(.2, .9, .5)), .625);
    }

    @Test void modelStraddlesWaterSurface() {
        check(List.of(new Fragment(.2, .9, .5), new Fragment(.4, .1, .5),
                new Fragment(.6, .7, .5)), .6375);
    }

    @Test void modelBetweenTwoColoredGlassLayers() {
        check(List.of(new Fragment(.2, .2, .5), new Fragment(.4, .8, .5),
                new Fragment(.6, .4, .5)), .425);
    }

    @Test void opaqueSceneStillRejectsAllHiddenTransparentLayers() {
        assertEquals(.6, peel(List.of(new Fragment(.6, 1, .8)), .4), 1e-8);
    }

    private static void check(List<Fragment> layers, double expected) {
        List<Fragment> shuffled = new ArrayList<>(layers);
        Collections.shuffle(shuffled, new Random(42));
        for (int batch : new int[]{1, 4, 16}) {
            assertEquals(expected, peel(layers, 1, batch), 1e-8);
            assertEquals(expected, peel(layers.reversed(), 1, batch), 1e-8);
            assertEquals(expected, peel(shuffled, 1, batch), 1e-8);
        }
    }

    private static double peel(List<Fragment> fragments, double sceneDepth) {
        return peel(fragments, sceneDepth, 1);
    }

    @Test void saturatedPixelsAndEmptyExtraPeelsDoNotChangeTheResult() {
        check(List.of(new Fragment(.2, .3, 1), new Fragment(.4, .9, .7)), .3);
        check(List.of(), .6);
    }

    private static double peel(List<Fragment> fragments, double sceneDepth, int batch) {
        double previous = 0, color = 0, alpha = 0;
        for (int pass = 1; pass <= 32; pass++) {
            Fragment nearest = null;
            for (Fragment f : fragments) {
                if (alpha >= 1) break;
                if (f.alpha <= 1.0 / 255 || f.depth <= previous || f.depth > sceneDepth) continue;
                if (nearest == null || f.depth < nearest.depth) nearest = f;
            }
            if (nearest == null) {
                previous = 1;
            } else {
                color += (1 - alpha) * nearest.color * nearest.alpha;
                alpha += (1 - alpha) * nearest.alpha;
                previous = nearest.depth;
            }
            if (pass % batch == 0 && nearest == null) break;
        }
        return color + (1 - alpha) * .6;
    }
}
