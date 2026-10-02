package lib.kasuga.rendering.output;

import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.camera.OwnedCamera;
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
}
