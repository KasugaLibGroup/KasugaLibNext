package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayeredModelSourceTest {
    @Test void immutableGeometryIsPlannedOnceWhileRotationAndLayoutsRemainLive() {
        class Planned extends LayeredModelSource {
            Object key = new Object(); int reads;
            @Override protected Object geometryKey() { return key; }
            @Override protected List<Element> elements() {
                reads++;
                return List.of(new Element(1, 2, 0, new ModelBounds(1,2,0,2,3,1)),
                        new Element(1, 4, 0, new ModelBounds(1,4,0,2,5,1)));
            }
            @Override protected void renderSlice(Slice slice, Context context) {}
            @Override protected Matrix4f localTransform(float partial) { return new Matrix4f().rotateZ(partial); }
        }
        var source = new Planned();
        var first = source.snapshot(LayerLayout.ASSEMBLED, 0);
        var rotated = source.snapshot(LayerLayout.ASSEMBLED, .7f);
        assertNotEquals(first.bounds(), rotated.bounds());
        for (int i=0;i<100;i++) source.snapshot(LayerLayout.ASSEMBLED.range(4,4).separation(i*.02), .4f);
        assertEquals(1,source.reads,"Unchanged geometry was scanned every displayed frame");
        source.snapshot(LayerLayout.ASSEMBLED.axis(LayerLayout.Axis.X),0);
        assertEquals(2,source.reads,"Each axis builds a separate layer plan");
        source.key=new Object();source.snapshot(LayerLayout.ASSEMBLED,0);
        assertEquals(3,source.reads,"Changed geometry must invalidate its cached layer plan");
    }
    private static final class Source extends LayeredModelSource {
        private final List<Element> geometry = List.of(
                new Element(-2, -3, 4, new ModelBounds(-2, -3, 4, -1, -2, 5)),
                new Element(1, 0, 5, new ModelBounds(1, 0, 5, 2, 1, 6)),
                new Element(3, 2, -1, new ModelBounds(3, 2, -1, 4, 3, 0)));
        private final List<Vector3f> rendered = new ArrayList<>();
        private Matrix4f transform = new Matrix4f();
        @Override protected List<Element> elements() { return geometry; }
        @Override protected Matrix4f localTransform(float partial) { return new Matrix4f(transform); }
        @Override protected void renderSlice(Slice slice, Context context) {
            for (var element : slice.elements()) rendered.add(context.pose().last().pose()
                    .transformPosition(new Vector3f(element.x(), element.y(), element.z())));
        }
    }

    @Test void explodedBoundsContainTheActualDrawAndRestoreTheCallersPose() {
        var source = new Source();
        source.transform.rotateZ((float) Math.PI / 2);
        var frame = source.snapshot(LayerLayout.ASSEMBLED.separation(2), .5f);
        var pose = new PoseStack();
        frame.render(new MinecraftModelSource.Context(pose, type -> { throw new AssertionError(); }, new Matrix4f(), .5f, 0));
        assertEquals(3, source.rendered.size());
        assertEquals(-6, source.rendered.get(1).x, 1e-5);
        assertEquals(1, source.rendered.get(1).y, 1e-5);
        assertEquals(-13, frame.bounds().minX(), 1e-4);
        assertEquals(3, frame.bounds().maxX(), 1e-4);
        assertEquals(new Matrix4f(), pose.last().pose());
    }

    @Test void selectingOneLayerKeepsFramingAndDisplacementStable() {
        var source = new Source();
        var layout = LayerLayout.ASSEMBLED.separation(1.5);
        var all = source.snapshot(layout, 0);
        var slice = source.snapshot(layout.range(0, 0), 0);
        assertEquals(all.bounds(), slice.bounds());
        assertEquals(1, slice.parts().size());
        assertEquals(0, slice.parts().getFirst().layer());
        assertEquals(4.5, slice.parts().getFirst().bounds().minY());
        var hidden = source.snapshot(layout.range(100, 100), 0);
        assertTrue(hidden.parts().isEmpty());
        assertEquals(all.bounds(), hidden.bounds());
    }

    @Test void axesAndCloseUseTheSameContract() {
        var source = new Source();
        var x = source.snapshot(LayerLayout.ASSEMBLED.axis(LayerLayout.Axis.X).separation(2), 0);
        assertEquals(List.of(-2, 1, 3), x.parts().stream().map(MinecraftModelSource.Part::layer).toList());
        assertEquals(14, x.bounds().maxX());
        source.close(); source.close();
        assertNull(source.snapshot(LayerLayout.ASSEMBLED, 0));
    }

    @Test void invalidTransformsAndFrameInputsAreRejected() {
        var source = new Source();
        assertThrows(IllegalArgumentException.class, () -> source.snapshot(LayerLayout.ASSEMBLED, Float.NaN));
        source.transform.m00(Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> source.snapshot(LayerLayout.ASSEMBLED, 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelBounds(2, 0, 0, 1, 1, 1));
    }
}
