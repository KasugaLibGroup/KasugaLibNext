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
    /** Pose at the current animation tick. */
    WorldCameraView pose();
    CameraAnimationPlayer animation();
    void updatePose(Supplier<WorldCameraView> pose);
    default void updatePose(WorldCameraView pose) { Objects.requireNonNull(pose); updatePose(() -> pose); }
    /** Direct edits capture the current animated pose and replace the provider, stopping its animation. */
    default void moveTo(double x, double y, double z) { updatePose(pose().withPosition(x, y, z)); }
    default void moveBy(double x, double y, double z) { updatePose(pose().moveBy(x, y, z)); }
    default void rotateTo(float yaw, float pitch, float roll) { updatePose(pose().withRotation(yaw, pitch, roll)); }
    default void rotateBy(float yaw, float pitch, float roll) { updatePose(pose().rotateBy(yaw, pitch, roll)); }
    default void setVerticalFov(float degrees) { updatePose(pose().withVerticalFov(degrees)); }
    default void zoom(double factor) { updatePose(pose().zoom(factor)); }
    void pause();
    void resume();
    @Override void close() throws Exception;
}
