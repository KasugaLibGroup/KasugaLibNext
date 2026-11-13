package lib.kasuga.scene;

import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.ModelBounds;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneEntryTest {
    @Test void pickingUsesTheLastDrawnPoseAndSnapshotEvenAfterAnotherCameraSamples() {
        var source = new Source();
        var host = new Host();
        var entry = new SceneModels.Entry(null, source, Vec3.ZERO).follow(host);
        var main = entry.frame(.25f, true);
        host.x = 100;
        var otherCamera = entry.frame(.75f, false);
        assertEquals(100.75, otherCamera.pose().origin().x);
        var pick = entry.pickingFrame(.9f);
        assertSame(main.pose(), pick.pose());
        assertSame(main.snapshot(), pick.snapshot());
        assertEquals(2, source.samples, "Picking must not re-sample a moving model with a different partial tick");
        entry.close();
    }

    @Test void aHiddenRemovedHostClosesTheSourceOnceAndCannotBePickedOrRevived() {
        var source = new Source();
        var host = new Host();
        var entry = new SceneModels.Entry(null, source, Vec3.ZERO).follow(host).visible(false);
        host.alive = false;
        assertNull(entry.frame(.5f, true));
        assertTrue(entry.isClosed());
        host.alive = true;
        assertNull(entry.pickingFrame(.5f));
        assertNull(entry.frame(.5f, false));
        entry.close();
        assertEquals(1, source.closes);
        assertEquals(0, source.samples);
        assertThrows(IllegalStateException.class, () -> entry.visible(true));
    }

    @Test void staticPlacementRemainsQueryableBeforeFirstDrawAndAfterEdits() {
        var source = new Source();
        var entry = new SceneModels.Entry(null, source, new Vec3(1, 2, 3));
        assertEquals(new Vec3(1, 2, 3), entry.pickingFrame(0).pose().origin());
        entry.position(new Vec3(4, 5, 6));
        assertEquals(new Vec3(4, 5, 6), entry.pickingFrame(0).pose().origin());
        entry.close();
        assertEquals(1, source.closes);
    }

    private static final class Host implements SceneHost {
        double x;
        boolean alive = true;
        @Override public boolean isAlive() { return alive; }
        @Override public ScenePose pose(float partial) { return new ScenePose(new Vec3(x + partial, 0, 0), new Matrix4f()); }
    }
    private static final class Source implements MinecraftModelSource {
        int closes, samples;
        @Override public Snapshot snapshot(LayerLayout layout, float partial) {
            samples++;
            return new Snapshot(new ModelBounds(0, 0, 0, 1, 1, 1), List.of());
        }
        @Override public void close() { closes++; }
    }
}
