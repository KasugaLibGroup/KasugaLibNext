package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CameraTypesTest {
    private static final WorldCameraView BASE = new WorldCameraView(1, 2, 3, 10, 20, 30, 70, 320, 180);
    private static class Resources implements OwnedCamera.Producer, OwnedCamera.Output {
        boolean closed, enabled = true;
        CameraRenderSettings settings = CameraRenderSettings.defaults();
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isClosed() { return closed; }
        public Optional<CameraRenderSettings> renderSettings() { return Optional.of(settings); }
        public void updateRenderSettings(CameraRenderSettings settings) { this.settings = settings; }
        public void close() { closed = true; }
    }
    private static OwnedCamera camera(CameraSource source) {
        var resources = new Resources();
        return new OwnedCamera("test:view", source, resources, resources, () -> {}, () -> {});
    }

    @Test void playerTracksHostPoseAndRejectsIndependentControls() throws Exception {
        var current = new AtomicReference<>(BASE);
        var camera = camera(new CameraSource.Player(partial -> current.get().moveBy(partial, 0, 0)));
        assertEquals(CameraType.PLAYER, camera.type());
        assertEquals(1.5, camera.samplePose(.5f).x());
        current.set(BASE.moveBy(8, 0, 0));
        assertEquals(10, camera.pose().x());
        assertThrows(UnsupportedOperationException.class, () -> camera.moveTo(0, 0, 0));
        assertThrows(UnsupportedOperationException.class, camera::animation);
        assertThrows(UnsupportedOperationException.class, () -> camera.setVerticalFov(50));
        assertThrows(UnsupportedOperationException.class, () -> camera.follow(partial -> null));
        assertTrue(camera.renderSettings().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> camera.updateRenderSettings(CameraRenderSettings.defaults()));
        assertEquals(CameraState.READY, camera.state());
        camera.pause(); assertEquals(CameraType.PLAYER, camera.type()); camera.resume(); camera.close();
    }

    @Test void fixedSamplesTargetOnceWithDoublePrecisionAndWorldOffsets() throws Exception {
        int[] samples = {0};
        var follow = new CameraFollowSettings(2, 3, 4, 5, 6, 7, true);
        var camera = camera(new CameraSource.Fixed(partial -> {
            samples[0]++; return new CameraTarget.Pose(70_000_000d + partial, 60, 8, 30, 20, 10);
        }, new CameraProjection(60, 640, 360), follow));
        assertEquals(CameraType.FIXED, camera.type());
        assertEquals(new WorldCameraView(70_000_002.25, 63, 12, 35, 26, 17, 60, 640, 360), camera.samplePose(.25f));
        assertEquals(1, samples[0]);
        assertThrows(UnsupportedOperationException.class, camera::animation);
        assertThrows(UnsupportedOperationException.class, () -> camera.updatePose(BASE));
        camera.close();
    }

    @Test void fixedProjectionAndTargetChangesPreserveTheBindingAndConfiguration() throws Exception {
        CameraTarget first = partial -> new CameraTarget.Pose(1, 2, 3, 40, 50, 60);
        var camera = camera(new CameraSource.Fixed(first, CameraProjection.from(BASE), CameraFollowSettings.defaults()));
        camera.setVerticalFov(55); camera.zoom(2);
        assertSame(first, camera.followTarget().orElseThrow());
        assertEquals(new CameraProjection(55, 320, 180).zoom(2).verticalFov(), camera.pose().verticalFov());
        camera.updateFollowSettings(new CameraFollowSettings(2, 3, 4, 10, 20, 30, false));
        CameraTarget second = partial -> new CameraTarget.Pose(8, 9, 10, 90, 80, 70);
        camera.follow(second); camera.setProjection(new CameraProjection(45, 800, 600));
        assertEquals(new WorldCameraView(10, 12, 14, 10, 20, 30, 45, 800, 600), camera.pose());
        assertThrows(IllegalArgumentException.class, () -> camera.zoom(0));
        assertSame(second, camera.followTarget().orElseThrow());
        camera.close();
        assertThrows(IllegalStateException.class, () -> camera.follow(first));
    }

    @Test void absentTargetWaitsAndCanRecoverOrChangeFovBeforeItsFirstFrame() throws Exception {
        var target = new AtomicReference<CameraTarget.Pose>();
        var camera = camera(new CameraSource.Fixed(partial -> target.get(), CameraProjection.from(BASE), CameraFollowSettings.defaults()));
        assertTrue(camera.sampleAvailablePose(.5f).isEmpty());
        assertThrows(IllegalStateException.class, camera::pose);
        assertEquals(CameraState.READY, camera.state());
        camera.setVerticalFov(40); camera.zoom(2);
        target.set(new CameraTarget.Pose(1, 2, 3, 4, 5, 6));
        assertEquals(1, camera.samplePose(.5f).x());
        assertEquals(new CameraProjection(40, 320, 180).zoom(2).verticalFov(), camera.pose().verticalFov());
        target.set(null); assertTrue(camera.sampleAvailablePose(.5f).isEmpty());
        camera.pause(); camera.resume();
        target.set(new CameraTarget.Pose(8, 9, 10, 4, 5, 6));
        assertEquals(8, camera.pose().x()); camera.close();
    }

    @Test void targetFailureRetiresOwnedResourcesButInvalidControlsDoNot() throws Exception {
        var camera = camera(new CameraSource.Fixed(partial -> { throw new IllegalStateException("source"); },
                CameraProjection.from(BASE), CameraFollowSettings.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new CameraFollowSettings(Double.NaN, 0, 0, 0, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new CameraProjection(180, 320, 180));
        assertEquals(CameraState.READY, camera.state());
        assertEquals("source", assertThrows(IllegalStateException.class, camera::pose).getMessage());
        assertEquals(CameraState.FAILED, camera.state()); assertTrue(camera.failure().isPresent());
        camera.close();
    }

    @Test void freeCameraKeepsLegacyAnimationAndMutableRenderSettings() throws Exception {
        var camera = camera(new CameraSource.Free(() -> BASE));
        assertEquals(CameraType.FREE, camera.type());
        camera.moveTo(5, 6, 7); camera.setProjection(new CameraProjection(40, 1920, 1080));
        assertEquals(new WorldCameraView(5, 6, 7, 10, 20, 30, 40, 1920, 1080), camera.pose());
        var settings = new CameraRenderSettings(4, CameraRenderSettings.Quality.FAST, CameraRenderSettings.Shader.disabled());
        camera.updateRenderSettings(settings); assertEquals(settings, camera.renderSettings().orElseThrow());
        assertNotNull(camera.animation()); assertTrue(camera.followTarget().isEmpty()); camera.close();
        assertThrows(IllegalStateException.class, () -> camera.updateRenderSettings(settings));
    }
}
