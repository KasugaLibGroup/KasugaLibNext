package lib.kasuga.rendering.output;

import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.camera.OwnedCamera;
import lib.kasuga.rendering.output.camera.CameraAnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedCameraTest {
    private static final WorldCameraView POSE = new WorldCameraView(1, 2, 3, 0, 0, 0, 70, 320, 180);
    private static final class Producer implements OwnedCamera.Producer {
        int closes; boolean enabled = true, closed; boolean throwClose;
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isClosed() { return closed; }
        public void close() throws Exception { closes++; closed = true; if (throwClose) throw new Exception("producer"); }
    }
    private static final class Output implements OwnedCamera.Output {
        int closes; boolean closed; boolean throwClose;
        public boolean isClosed() { return closed; }
        public void close() throws Exception { closes++; closed = true; if (throwClose) throw new Exception("output"); }
    }

    @Test void ownsBothSidesAndSamplesTheCurrentProvider() throws Exception {
        Producer producer = new Producer(); Output output = new Output();
        int[] retired = {0};
        OwnedCamera camera = new OwnedCamera("test:a", () -> POSE, producer, output, () -> {}, () -> retired[0]++);
        camera.pause(); assertEquals(CameraState.PAUSED, camera.state()); assertFalse(producer.enabled);
        camera.updatePose(new WorldCameraView(4, 5, 6, 45, 10, 0, 50, 480, 270));
        camera.resume(); assertTrue(producer.enabled); assertEquals(4, camera.samplePose().x());
        camera.close(); camera.close();
        assertEquals(CameraState.CLOSED, camera.state()); assertEquals(1, producer.closes);
        assertEquals(1, output.closes); assertEquals(1, retired[0]);
        assertThrows(IllegalStateException.class, camera::resume);
        assertThrows(IllegalStateException.class, camera::samplePose);
    }

    @Test void cleanupFailureDoesNotStrandOtherOwners() {
        Producer producer = new Producer(); Output output = new Output();
        producer.throwClose = output.throwClose = true;
        int[] retired = {0};
        OwnedCamera camera = new OwnedCamera("test:a", () -> POSE, producer, output, () -> {}, () -> retired[0]++);
        Exception failure = assertThrows(Exception.class, camera::close);
        assertEquals("producer", failure.getMessage()); assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, output.closes); assertEquals(1, retired[0]);
        assertEquals(CameraState.CLOSED, camera.state());
    }

    @Test void providerFailureRetiresCameraAndPreservesItsCause() throws Exception {
        Producer producer = new Producer(); Output output = new Output();
        IllegalStateException failure = new IllegalStateException("bad pose");
        OwnedCamera camera = new OwnedCamera("test:a", () -> { throw failure; }, producer, output, () -> {}, () -> {});
        assertSame(failure, assertThrows(IllegalStateException.class, camera::samplePose));
        assertEquals(CameraState.FAILED, camera.state()); assertSame(failure, camera.failure().orElseThrow());
        camera.close(); assertEquals(1, producer.closes); assertEquals(1, output.closes);
    }

    @Test void eachCameraRetainsItsOwnPoseAndLifecycle() throws Exception {
        OwnedCamera a = new OwnedCamera("test:a", () -> POSE, new Producer(), new Output(), () -> {}, () -> {});
        OwnedCamera b = new OwnedCamera("test:b", () -> POSE, new Producer(), new Output(), () -> {}, () -> {});
        a.updatePose(new WorldCameraView(8, 9, 10, 0, 0, 0, 70, 320, 180)); a.close();
        assertEquals(CameraState.READY, b.state()); assertEquals(POSE, b.samplePose()); b.close();
    }

    @Test void directControlsCaptureAnimationAndKeepOtherPoseComponents() throws Exception {
        var camera = new OwnedCamera("test:controls", () -> POSE, new Producer(), new Output(), () -> {}, () -> {});
        camera.animation().play(CameraAnimationClip.builder(Id.parse("test:move"), 1)
                .move(0, 1, 2, 3, null).move(1, 11, 2, 3, null).build(), false);
        camera.tick(.5f);
        assertEquals(3.5, camera.samplePose(.5f).x());
        camera.moveBy(2, 3, 4);
        assertEquals(8, camera.pose().x()); assertEquals(5, camera.pose().y()); assertEquals(7, camera.pose().z());
        assertNull(camera.animation().currentClip());
        camera.rotateTo(30, 10, 20); camera.rotateBy(5, -2, 1); camera.zoom(2);
        assertEquals(35, camera.pose().yaw()); assertEquals(8, camera.pose().pitch()); assertEquals(21, camera.pose().roll());
        assertEquals(POSE.zoom(2).verticalFov(), camera.pose().verticalFov());
        camera.setVerticalFov(60); camera.moveTo(9, 8, 7);
        assertEquals(new WorldCameraView(9, 8, 7, 35, 8, 21, 60, 320, 180), camera.pose());
        camera.close();
    }

    @Test void cameraPauseAndCloseGovernItsAnimationClockAndRetainedController() throws Exception {
        var camera = new OwnedCamera("test:clock", () -> POSE, new Producer(), new Output(), () -> {}, () -> {});
        var animation = camera.animation();
        animation.play(CameraAnimationClip.builder(Id.parse("test:clock"), 1)
                .move(0, 1, 2, 3, null).move(1, 11, 2, 3, null).build(), true);
        camera.tick(.25f); camera.pause(); camera.tick(.5f);
        assertEquals(.25, animation.currentTime());
        camera.resume(); camera.tick(.25f); assertEquals(.5, animation.currentTime());
        camera.close();
        assertThrows(IllegalStateException.class, animation::resume);
        assertThrows(IllegalStateException.class, () -> camera.moveBy(1, 0, 0));
    }

    @Test void invalidDirectControlLeavesTheCurrentAnimationIntact() throws Exception {
        var camera = new OwnedCamera("test:invalid", () -> POSE, new Producer(), new Output(), () -> {}, () -> {});
        var clip = CameraAnimationClip.builder(Id.parse("test:clip"), 1).zoom(0, 2, null).build();
        camera.animation().play(clip, true);
        assertThrows(IllegalArgumentException.class, () -> camera.zoom(0));
        assertSame(clip, camera.animation().currentClip());
        assertEquals(CameraState.READY, camera.state()); camera.close();
    }
}
