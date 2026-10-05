package lib.kasuga.rendering.cloud;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CumulonimbusVolumeTest {
    @Test void skyCullingIgnoresTerrainFarPlaneButRejectsBehindAndSideViews() {
        var projection = new org.joml.Matrix4f().perspective(.9f, 1.5f, .1f, 256);
        var cloud = new CloudPose(29_999_999.125, 100, -2000, 1600, 1200, 1200, 37);
        assertTrue(cloud.isVisible(projection, 29_999_999, 100, 0));
        assertFalse(cloud.moveTo(cloud.x(), 100, 2000).isVisible(projection, 29_999_999, 100, 0));
        assertFalse(cloud.moveBy(10000, 0, 0).isVisible(projection, 29_999_999, 100, 0));
    }

    @Test void bakedShapeRetainsTowerAndAnvilControls() {
        int size = 12;
        var grid = CumulonimbusShape.bake(size);
        assertEquals(size * size * size * 2, grid.length);
        for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int i = ((z * size + y) * size + x) * 2;
            float px = (x + .5f) / size * 2 - 1, py = (y + .5f) / size, pz = (z + .5f) / size * 2 - 1;
            assertEquals(CumulonimbusShape.envelope(px, py, pz, .5f), Math.max(grid[i], grid[i + 1] * .5f), 1e-6);
        }
    }

    @Test void windAdvancesOnlyOnTicksAndAllViewsSampleTheSameFrame() {
        var cloud = new CumulonimbusVolume(CloudPose.at(100, 200, 300), CloudSettings.CUMULONIMBUS);
        cloud.wind(20, -10, 5); cloud.tick(.05);
        var frame = cloud.sample(.5f);
        assertEquals(100.5, frame.pose().x()); assertEquals(199.75, frame.pose().y());
        assertEquals(300.125, frame.pose().z()); assertEquals(.025, frame.timeSeconds());
        for (int i = 0; i < 20; i++) assertEquals(frame, cloud.sample(.5f));
        assertEquals(101, cloud.pose().x());
    }

    @Test void directControlsReplaceTheInterpolatedPose() {
        var cloud = new CumulonimbusVolume(CloudPose.at(0, 0, 0), CloudSettings.CUMULONIMBUS);
        cloud.wind(20, 0, 0); cloud.tick(.05); cloud.moveTo(10, 20, 30);
        cloud.rotateBy(90); cloud.scale(2, 1, .5f);
        assertEquals(cloud.pose(), cloud.sample(0).pose());
        assertEquals(3200, cloud.pose().width()); assertEquals(600, cloud.pose().depth());
        assertEquals(90, cloud.pose().yaw());
        assertThrows(IllegalArgumentException.class, () -> cloud.scale(-1, 1, 1));
        assertEquals(3200, cloud.pose().width());
    }

    @Test void boundsContainYawRotatedVolumeAndUseBaseAsOrigin() {
        var pose = new CloudPose(10, 50, -20, 120, 200, 60, 37);
        var localToWorld = pose.worldToLocal(0, 0, 0).invert();
        var bounds = pose.bounds();
        for (float x : new float[]{-1, 1}) for (float y : new float[]{0, 1}) for (float z : new float[]{-1, 1}) {
            var world = localToWorld.transformPosition(new Vector3f(x, y, z));
            assertTrue(world.x >= bounds.minX() - .0001 && world.x <= bounds.maxX() + .0001);
            assertTrue(world.y >= bounds.minY() - .0001 && world.y <= bounds.maxY() + .0001);
            assertTrue(world.z >= bounds.minZ() - .0001 && world.z <= bounds.maxZ() + .0001);
        }
    }

    @Test void cameraRelativeSubtractionRetainsPrecisionAtWorldBorder() {
        var pose = new CloudPose(29_999_999.125, 100, -29_999_999.25, 100, 100, 100, 0);
        var center = pose.worldToLocal(29_999_999, 100, -29_999_999).transformPosition(new Vector3f(.125f, 0, -.25f));
        assertEquals(0, center.x, 1e-6); assertEquals(0, center.z, 1e-6);
    }

    @Test void invalidValuesAreRejectedBeforeChangingTheInstance() {
        var cloud = new CumulonimbusVolume(CloudPose.at(0, 0, 0), CloudSettings.CUMULONIMBUS);
        assertThrows(IllegalArgumentException.class, () -> cloud.tick(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> cloud.tick(-1));
        assertThrows(IllegalArgumentException.class, () -> cloud.moveBy(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> cloud.wind(0, Double.NaN, 0));
        assertEquals(CloudPose.at(0, 0, 0), cloud.pose()); assertEquals(0, cloud.sample(1).timeSeconds());
        assertThrows(IllegalArgumentException.class, () -> CloudSettings.CUMULONIMBUS.withDensity(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new CloudPose(0, 0, 0, 0, 1, 1, 0));
    }

    @Test void removalIsIdempotentAndRejectsFurtherUpdates() {
        var cloud = new CumulonimbusVolume(CloudPose.at(0, 0, 0), CloudSettings.CUMULONIMBUS);
        cloud.close(); cloud.close(); assertFalse(cloud.isAlive());
        assertThrows(IllegalStateException.class, () -> cloud.tick(.05));
        assertThrows(IllegalStateException.class, () -> cloud.moveBy(1, 0, 0));
    }

    @Test void cumulonimbusHasAFlatBaseTallCoreAndControllableAnvil() {
        assertEquals(0, CumulonimbusShape.envelope(0, -.01f, 0, 1));
        assertEquals(0, CumulonimbusShape.envelope(0, 1.01f, 0, 1));
        assertTrue(CumulonimbusShape.envelope(-.16f, .45f, 0, 1) > .5f);
        assertTrue(CumulonimbusShape.envelope(.7f, .85f, 0, 1) > .3f);
        assertEquals(0, CumulonimbusShape.envelope(.7f, .85f, 0, 0));
        for (var lobe : CumulonimbusShape.LOBES) {
            assertTrue(Math.abs(lobe.x()) + lobe.rx() <= 1);
            assertTrue(lobe.y() + lobe.ry() <= 1);
            assertTrue(Math.abs(lobe.z()) + lobe.rz() <= 1);
        }
    }

    @Test void noiseIsPeriodicDeterministicAndDefensivelyOwned() {
        assertEquals(CloudNoise.value(.123, .45, .67, 8), CloudNoise.value(1.123, -.55, 2.67, 8), 1e-12);
        var first = CloudNoise.generate(8); var second = CloudNoise.generate(8);
        assertArrayEquals(first.pixels(), second.pixels());
        var pixels = first.pixels(); Arrays.fill(pixels, (byte) 0);
        assertFalse(Arrays.equals(pixels, first.pixels()));
        long distinct = java.util.stream.IntStream.range(0, pixels.length).map(i -> first.pixels()[i] & 255).distinct().count();
        assertTrue(distinct > 32);
    }
}
