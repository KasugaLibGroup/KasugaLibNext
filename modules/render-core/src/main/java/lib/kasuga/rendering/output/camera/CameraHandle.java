package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/** Owns a camera producer and its primary output; all operations belong to the render thread. */
public interface CameraHandle extends AutoCloseable {
    String viewId();
    default CameraType type() { return CameraType.FREE; }
    CameraState state();
    Optional<Throwable> failure();
    /** Pose at the current animation tick. */
    WorldCameraView pose();
    CameraAnimationPlayer animation();
    /** Empty for a player view, whose rendering configuration belongs to the host. */
    default Optional<CameraRenderSettings> renderSettings() { return Optional.empty(); }
    default void updateRenderSettings(CameraRenderSettings settings) {
        throw new UnsupportedOperationException("This camera uses host render settings");
    }
    /** Free and fixed cameras can change their projection without changing the follow target. */
    default void setProjection(CameraProjection projection) { updatePose(Objects.requireNonNull(projection).apply(pose())); }
    default Optional<CameraTarget> followTarget() { return Optional.empty(); }
    default CameraFollowSettings followSettings() { throw new UnsupportedOperationException("Camera is not fixed"); }
    default void follow(CameraTarget target) { throw new UnsupportedOperationException("Camera is not fixed"); }
    default void updateFollowSettings(CameraFollowSettings settings) { throw new UnsupportedOperationException("Camera is not fixed"); }
    void updatePose(Supplier<WorldCameraView> pose);
    default void updatePose(WorldCameraView pose) { Objects.requireNonNull(pose); updatePose(() -> pose); }
    /** Direct edits capture the current animated pose and replace the provider, stopping its animation. */
    default void moveTo(double x, double y, double z) { updatePose(pose().withPosition(x, y, z)); }
    default void moveBy(double x, double y, double z) { updatePose(pose().moveBy(x, y, z)); }
    default void rotateTo(float yaw, float pitch, float roll) { updatePose(pose().withRotation(yaw, pitch, roll)); }
    default void rotateBy(float yaw, float pitch, float roll) { updatePose(pose().rotateBy(yaw, pitch, roll)); }
    default void setVerticalFov(float degrees) { setProjection(CameraProjection.from(pose()).withVerticalFov(degrees)); }
    default void zoom(double factor) { setProjection(CameraProjection.from(pose()).zoom(factor)); }
    void pause();
    void resume();
    @Override void close() throws Exception;
}
