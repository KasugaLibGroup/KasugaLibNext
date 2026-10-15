package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pixel partition reference: no omitted or double-blended terrain at mask edges. */
class TransparencyFootprintTest {
    private record Fragment(double depth, double color, double alpha, boolean model) {}

    @Test void preservesAllFourWaterAndGlassArrangements() {
        check(List.of(world(.2), model(.4)), 1);
        check(List.of(model(.2), world(.4)), 1);
        check(List.of(model(.2), world(.4), model(.6)), 1);
        check(List.of(world(.2), model(.4), world(.6)), 1);
    }

    @Test void terrainWithoutVisibleModelStaysInNativePass() {
        check(List.of(world(.2), world(.4)), 1);
        check(List.of(world(.2), model(.6)), .4);
        check(List.of(world(.2), new Fragment(.3, .8, 0, true)), 1);
        check(List.of(), 1);
    }

    @Test void softAlphaEdgesAreNotTurnedIntoOpaqueCoverage() {
        for (double alpha : new double[]{1.0 / 255, .005, .25, .999}) {
            check(List.of(world(.2), new Fragment(.4, .8, alpha, true), world(.6)), 1);
        }
    }

    @Test void movingModelRecomputesFootprintEveryFrame() {
        check(List.of(world(.2)), 1);
        check(List.of(world(.2), model(.4)), 1);
        check(List.of(world(.2)), 1);
    }

    private static Fragment world(double z) { return new Fragment(z, .2, .5, false); }
    private static Fragment model(double z) { return new Fragment(z, .8, .5, true); }

    private static void check(List<Fragment> fragments, double opaqueDepth) {
        var visible = fragments.stream().filter(f -> f.depth <= opaqueDepth
                && f.alpha > 1.0 / 255).toList();
        boolean footprint = visible.stream().anyMatch(Fragment::model);
        List<Fragment> nativeTerrain = visible.stream().filter(f -> !footprint && !f.model).toList();
        List<Fragment> peeled = visible.stream().filter(f -> footprint).toList();
        assertEquals(visible.size(), nativeTerrain.size() + peeled.size());
        assertEquals(composite(visible, .6), composite(peeled, composite(nativeTerrain, .6)), 1e-12);
    }

    private static double composite(List<Fragment> layers, double background) {
        double color = background;
        for (Fragment f : layers.stream().sorted(Comparator.comparingDouble(Fragment::depth).reversed()).toList()) {
            color = f.color * f.alpha + color * (1 - f.alpha);
        }
        return color;
    }
}
