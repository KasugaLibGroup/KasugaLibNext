package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/** Owns a camera producer and its primary output; all operations belong to the render thread. */
public interface CameraHandle extends AutoCloseable {
    String viewId();
    CameraState state();
    Optional<Throwable> failure();
    void updatePose(Supplier<WorldCameraView> pose);
    default void updatePose(WorldCameraView pose) { Objects.requireNonNull(pose); updatePose(() -> pose); }
    void pause();
    void resume();
    @Override void close() throws Exception;
}
