package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCompositionTest {
    private static final class Source implements MinecraftModelSource {
        private final ModelBounds bounds = new ModelBounds(0, 0, 0, 1, 1, 1);
        private Vector3f drawn;
        private int closed;
        private boolean failClose;
        @Override public Snapshot snapshot(LayerLayout layout, float partial) {
            return new Snapshot(bounds, List.of(new Part("fixture", 0, bounds,
                    context -> drawn = context.pose().last().pose().transformPosition(new Vector3f()))));
        }
        @Override public void close() { closed++; if (failClose) throw new IllegalStateException("fixture cleanup failure"); }
    }

    @Test void composedDrawsAndBoundsUseIndependentDefensivePlacements() {
        var left = new Source(); var right = new Source();
        var original = new Matrix4f().translate(3, 0, 0);
        var placement = new MinecraftModelSources.Placement(right, original);
        original.translate(100, 0, 0); placement.transform().translate(100, 0, 0);
        var source = MinecraftModelSources.compose(new MinecraftModelSources.Placement(left, new Matrix4f()), placement);
        var frame = source.snapshot(LayerLayout.ASSEMBLED, .5f);
        assertEquals(new ModelBounds(0, 0, 0, 4, 1, 1), frame.bounds());
        assertEquals(2, frame.parts().size());
        var pose = new PoseStack();
        frame.render(new MinecraftModelSource.Context(pose, type -> { throw new AssertionError(); }, new Matrix4f(), .5f, 0));
        assertEquals(new Vector3f(), left.drawn); assertEquals(new Vector3f(3, 0, 0), right.drawn);
        assertEquals(new Matrix4f(), pose.last().pose());
    }

    @Test void closesEveryChildOnceEvenWhenOneCleanupFails() {
        var first = new Source(); first.failClose = true; var second = new Source();
        var source = MinecraftModelSources.compose(new MinecraftModelSources.Placement(first, new Matrix4f()),
                new MinecraftModelSources.Placement(second, new Matrix4f()));
        assertThrows(IllegalStateException.class, source::close);
        source.close();
        assertEquals(1, first.closed); assertEquals(1, second.closed);
        assertNull(source.snapshot(LayerLayout.ASSEMBLED, 0));
    }

    @Test void rejectsAmbiguousOwnershipAndSingularPlacements() {
        var child = new Source();
        var p = new MinecraftModelSources.Placement(child, new Matrix4f());
        assertThrows(IllegalArgumentException.class, () -> MinecraftModelSources.compose(p, p));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftModelSources.Placement(child, new Matrix4f().scale(0)));
        assertEquals(0, child.closed, "An invalid composition must not take ownership of a borrowed source");
    }
}
