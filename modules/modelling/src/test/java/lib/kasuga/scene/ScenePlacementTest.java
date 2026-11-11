package lib.kasuga.scene;

import lib.kasuga.rendering.models.mc.backend.ui.WorldUiInputRouter;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScenePlacementTest {
    @Test void movingPanelKeepsSubpixelPickingAtThirtyMillionBlocks() {
        Vec3 camera = new Vec3(29_999_998.125, 80.375, -29_999_999.625);
        Vec3 origin = camera.add(-.5, .5, -3);
        var pose = new ScenePose(origin, new Matrix4f().rotateY(.3f).scale(.003f, -.003f, .003f));
        var router = new WorldUiInputRouter();
        router.beginFrame(new Matrix4f().perspective(.9f, 16f / 9, .05f, 100), 854, 480, 0, 0);
        var listener = new GuiEventListener() {
            @Override public void setFocused(boolean value) {}
            @Override public boolean isFocused() { return false; }
        };
        router.addSurface(listener, pose.relativeTo(camera), 260, 240);
        var screen = router.screenPosition(listener, 35, 34);
        assertNotNull(screen);
        router.move(screen.x, screen.y);
        assertEquals(35, router.positionOn(listener).x, .002);
        assertEquals(34, router.positionOn(listener).y, .002);
        var expected = new Matrix4f().translation(-.5f, .5f, -3).rotateY(.3f).scale(.003f, -.003f, .003f);
        assertEquals(0, pose.relativeTo(camera).transformPosition(new Vector3f(35, 34, 0))
                .distance(expected.transformPosition(new Vector3f(35, 34, 0))), 1e-6);
    }

    @Test void hostRotationLocalOffsetAndScaleComposeOnceAndRoundTripInDoublePrecision() {
        var host = new ScenePose(new Vec3(29_999_999.125, 42, -29_999_999.375),
                new Matrix4f().rotateY(.7f).rotateZ(-.4f));
        Vec3 offset = new Vec3(3.25, 1.75, -2.5);
        var local = new Matrix4f().translate(.25f, .125f, 0).scale(2, .5f, 3);
        var placed = host.compose(offset, local);
        var point = new Vec3(.125, 2.5, -3.25);
        var localPoint = local.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        assertEquals(0, host.toWorld(offset.add(localPoint.x, localPoint.y, localPoint.z))
                .distanceTo(placed.toWorld(point)), 1e-6);
        assertEquals(0, point.distanceTo(placed.toLocal(placed.toWorld(point))), 1e-6);
    }

    @Test void sampledPlacementKeepsDrawnFrameUntilNextCaptureAndExpiresPermanently() {
        var host = new Host();
        var placement = new ScenePlacement(Vec3.ZERO).follow(host).position(new Vec3(1, 0, 0));
        var drawn = placement.capture(.25f);
        assertEquals(1.25, drawn.origin().x, 1e-6);
        host.x = 100;
        assertSame(drawn, placement.lastFrame());
        assertEquals(101.75, placement.capture(.75f).origin().x, 1e-6);
        host.alive = false;
        assertNull(placement.capture(1));
        assertFalse(placement.isAlive());
        host.alive = true;
        assertNull(placement.capture(1), "An unloaded attachment must not silently bind a new host");
    }

    @Test void poseCopiesInputAndOutputMatricesAndRejectsInvalidTransforms() {
        var input = new Matrix4f().translate(.25f, .5f, .75f).rotateY(.2f);
        var pose = new ScenePose(new Vec3(2, 3, 4), input);
        input.zero();
        pose.transform().zero();
        assertEquals(new Vec3(2.25, 3.5, 4.75), pose.origin());
        assertTrue(pose.transform().isFinite());
        assertNotEquals(0, pose.transform().determinant());
        assertThrows(IllegalArgumentException.class, () -> new ScenePose(Vec3.ZERO, new Matrix4f().zero()));
        assertThrows(IllegalArgumentException.class, () -> new ScenePose(new Vec3(Double.NaN, 0, 0), new Matrix4f()));
        assertThrows(IllegalArgumentException.class, () -> new ScenePose(Vec3.ZERO, new Matrix4f().perspective(1, 1, .1f, 10)));
        assertThrows(IllegalArgumentException.class, () -> new ScenePlacement(Vec3.ZERO).capture(Float.NaN));
    }

    private static final class Host implements SceneHost {
        boolean alive = true;
        double x;
        @Override public boolean isAlive() { return alive; }
        @Override public ScenePose pose(float partialTick) {
            return new ScenePose(new Vec3(x + partialTick, 0, 0), new Matrix4f());
        }
    }
}
