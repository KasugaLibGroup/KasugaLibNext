package lib.kasuga.rendering.lighting;

import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.rendering.models.mc.scene.display.WorldLightTarget;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SceneLightTest {
    @Test void nestedScenesComposeMovementAndVisibilityWithoutAdvancingTime() {
        WorldDisplayScene root = new WorldDisplayScene();
        WorldDisplayScene group = root.add(new WorldDisplayScene());
        WorldLightTarget light = group.add(new WorldLightTarget(LightSource.point(1, 2, 3).build()));
        root.transform(new Matrix4f().translation(10, 0, 0));
        group.transform(new Matrix4f().translation(0, 20, 0));
        assertEquals(new Vector3d(11, 22, 3), position(root));
        light.moveBy(2, 0, -1);
        assertEquals(new Vector3d(13, 22, 2), position(root));
        light.position(new Vec3(0, 0, 0));
        assertEquals(new Vector3d(10, 20, 0), position(root));
        group.visible(false);
        assertTrue(root.lights().isEmpty());
        group.visible(true);
        assertEquals(new Vector3d(10, 20, 0), position(root));
        group.remove(light);
        assertTrue(light.isClosed());
        assertTrue(root.lights().isEmpty());
        assertThrows(IllegalStateException.class, () -> light.moveBy(1, 0, 0));
        root.close();
        assertTrue(group.isClosed());
    }

    @Test void placementPreservesRollAreaSizeAndDistantWorldPrecision() {
        LightSource panel = LightSource.area(1, 0, 0, 4, 6).build();
        Matrix4d placement = new Matrix4d().translation(29_999_999.125, 64, -29_999_999.25)
                .rotateZ(Math.PI / 2).scale(2, 3, 1);
        LightFrame frame = new LightFrame(panel, placement);
        placement.zero();
        frame.transform().zero();
        ByteBuffer data = ByteBuffer.allocate(LightBuffer.BYTES_PER_LIGHT).order(ByteOrder.nativeOrder());
        LightBuffer.write(data, frame, 29_999_999, 64, -29_999_999);
        assertEquals(.125, data.getFloat(0), 1e-6);
        assertEquals(2, data.getFloat(4), 1e-6);
        assertEquals(-.25, data.getFloat(8), 1e-6);
        assertEquals(48, data.getFloat(12), 1e-6);
        assertEquals(2, data.getFloat(36), 1e-6); // U points up, retaining scale 2.
        assertEquals(-3, data.getFloat(48), 1e-6); // V points left, retaining scale 3.
        assertEquals(3, frame.rangeScale(), 1e-6);
    }

    @Test void closingAncestorClosesAndRemovesEveryLightInTheSubtree() {
        WorldDisplayScene root = new WorldDisplayScene();
        WorldDisplayScene child = root.add(new WorldDisplayScene());
        WorldLightTarget point = child.add(new WorldLightTarget(LightSource.point(0, 0, 0).build()));
        WorldLightTarget fog = child.add(new WorldLightTarget(LightSource.volumetric(0, 0, 0).build()));
        assertEquals(2, root.lights().size());
        root.close();
        assertTrue(root.lights().isEmpty());
        assertTrue(point.isClosed());
        assertTrue(fog.isClosed());
    }

    private static Vector3d position(WorldDisplayScene scene) {
        WorldDisplayTarget.Light light = scene.lights().getFirst();
        return new LightFrame(light.source(), new Matrix4d(light.transform())).position();
    }
}
