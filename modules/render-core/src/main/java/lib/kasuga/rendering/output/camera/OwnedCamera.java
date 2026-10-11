package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Composite lifecycle. Releases every owned resource even when one cleanup fails. */
public final class OwnedCamera implements CameraHandle {
    public interface Producer extends AutoCloseable {
        void setEnabled(boolean enabled);
        boolean isClosed();
        default Optional<Throwable> failure() { return Optional.empty(); }
        default Optional<CameraRenderSettings> renderSettings() { return Optional.empty(); }
        default void updateRenderSettings(CameraRenderSettings settings) {
            throw new UnsupportedOperationException("This producer uses host render settings");
        }
    }
    public interface Output extends AutoCloseable { boolean isClosed(); }

    private final String viewId;
    private final CameraType type;
    private final Producer producer;
    private final Output output;
    private final Runnable checkThread;
    private final Runnable onClosed;
    private final CameraAnimationPlayer animation;
    private CameraSource source;
    private CameraState state = CameraState.READY;
    private Throwable failure;

    public OwnedCamera(String viewId, Supplier<WorldCameraView> pose, Producer producer,
                       Output output, Runnable checkThread, Runnable onClosed) {
        this(viewId, new CameraSource.Free(pose), producer, output, checkThread, onClosed);
    }

    public OwnedCamera(String viewId, CameraSource source, Producer producer,
                       Output output, Runnable checkThread, Runnable onClosed) {
        this.viewId = Objects.requireNonNull(viewId);
        this.source = Objects.requireNonNull(source);
        this.type = source.type();
        this.producer = Objects.requireNonNull(producer);
        this.output = Objects.requireNonNull(output);
        this.checkThread = Objects.requireNonNull(checkThread);
        this.onClosed = Objects.requireNonNull(onClosed);
        this.animation = source instanceof CameraSource.Free free
                ? new CameraAnimationPlayer(free.provider(), this::checkAccess) : null;
    }

    public String viewId() { return viewId; }
    public CameraType type() { return type; }
    public CameraState state() {
        checkThread.run();
        if (state != CameraState.CLOSED && state != CameraState.FAILED && (producer.isClosed() || output.isClosed()))
            fail(producer.failure().orElseGet(() -> new IllegalStateException("Camera producer or output was detached")));
        return state;
    }
    public Optional<Throwable> failure() { checkThread.run(); return Optional.ofNullable(failure); }

    /** Called once by the renderer; providers cannot return a null pose. */
    public WorldCameraView samplePose() {
        return samplePose(1);
    }

    public WorldCameraView samplePose(float partialTick) {
        return sampleAvailablePose(partialTick).orElseThrow(() -> new IllegalStateException("Follow target is unavailable"));
    }

    /** An absent fixed target skips rendering without failing the camera or retiring its output. */
    public Optional<WorldCameraView> sampleAvailablePose(float partialTick) {
        checkThread.run();
        ensureUsable();
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Partial tick must be finite");
        try {
            WorldCameraView view = switch (source) {
                case CameraSource.Free ignored -> animation.sample(partialTick);
                case CameraSource.Player player -> Objects.requireNonNull(player.provider().sample(partialTick), "Player pose");
                case CameraSource.Fixed fixed -> {
                    var target = fixed.target().sample(partialTick);
                    yield target == null ? null : fixed.follow().apply(target, fixed.projection());
                }
            };
            return Optional.ofNullable(view);
        }
        catch (RuntimeException failure) { fail(failure); throw failure; }
    }

    public WorldCameraView pose() { return samplePose(); }
    public CameraAnimationPlayer animation() { checkAccess(); requireType(CameraType.FREE); return animation; }
    public Optional<CameraRenderSettings> renderSettings() {
        checkAccess(); return type() == CameraType.PLAYER ? Optional.empty() : producer.renderSettings();
    }
    public void updateRenderSettings(CameraRenderSettings settings) {
        checkAccess(); Objects.requireNonNull(settings);
        if (type() == CameraType.PLAYER) throw new UnsupportedOperationException("Player camera uses host render settings");
        producer.updateRenderSettings(settings);
    }

    public void setProjection(CameraProjection projection) {
        checkAccess(); Objects.requireNonNull(projection);
        if (source instanceof CameraSource.Fixed fixed)
            source = new CameraSource.Fixed(fixed.target(), projection, fixed.follow());
        else { requireType(CameraType.FREE); updatePose(projection.apply(pose())); }
    }
    @Override public void setVerticalFov(float degrees) {
        checkAccess();
        if (source instanceof CameraSource.Fixed fixed) setProjection(fixed.projection().withVerticalFov(degrees));
        else CameraHandle.super.setVerticalFov(degrees);
    }
    @Override public void zoom(double factor) {
        checkAccess();
        if (source instanceof CameraSource.Fixed fixed) setProjection(fixed.projection().zoom(factor));
        else CameraHandle.super.zoom(factor);
    }
    public Optional<CameraTarget> followTarget() {
        checkAccess(); return source instanceof CameraSource.Fixed fixed ? Optional.of(fixed.target()) : Optional.empty();
    }
    public CameraFollowSettings followSettings() { checkAccess(); requireType(CameraType.FIXED); return ((CameraSource.Fixed) source).follow(); }
    public void follow(CameraTarget target) {
        checkAccess(); requireType(CameraType.FIXED);
        var fixed = (CameraSource.Fixed) source;
        source = new CameraSource.Fixed(target, fixed.projection(), fixed.follow());
    }
    public void updateFollowSettings(CameraFollowSettings settings) {
        checkAccess(); requireType(CameraType.FIXED);
        var fixed = (CameraSource.Fixed) source;
        source = new CameraSource.Fixed(fixed.target(), fixed.projection(), settings);
    }

    /** Pausing the camera also pauses clock advancement; disabled cameras do not render. */
    public void tick(float dt) {
        checkThread.run();
        if (state() == CameraState.READY && animation != null) animation.tick(dt);
    }

    public void updatePose(Supplier<WorldCameraView> pose) {
        checkAccess(); requireType(CameraType.FREE); animation.updatePose(pose);
        source = new CameraSource.Free(pose);
    }
    public void pause() {
        checkThread.run(); ensureUsable(); producer.setEnabled(false); state = CameraState.PAUSED;
    }
    public void resume() {
        checkThread.run(); ensureUsable(); producer.setEnabled(true); state = CameraState.READY;
    }

    public void fail(Throwable cause) {
        checkThread.run();
        if (state == CameraState.CLOSED || state == CameraState.FAILED) return;
        failure = Objects.requireNonNull(cause);
        state = CameraState.FAILED;
        try { release(); } catch (Exception cleanup) { if (cleanup != cause) cause.addSuppressed(cleanup); }
    }

    public void close() throws Exception {
        checkThread.run();
        if (state == CameraState.CLOSED) return;
        CameraState previous = state;
        state = CameraState.CLOSED;
        if (previous != CameraState.FAILED) release();
    }

    private void ensureUsable() {
        if (state == CameraState.CLOSED || state == CameraState.FAILED)
            throw new IllegalStateException("Camera is " + state, failure);
    }

    private void checkAccess() { checkThread.run(); ensureUsable(); }
    private void requireType(CameraType type) {
        if (type() != type) throw new UnsupportedOperationException("Operation requires " + type + " camera; current type is " + type());
    }

    private void release() throws Exception {
        source = null;
        if (animation != null) animation.detach();
        Exception first = null;
        try { producer.close(); } catch (Exception failure) { first = failure; }
        try { output.close(); } catch (Exception failure) {
            if (first == null) first = failure; else if (failure != first) first.addSuppressed(failure);
        }
        try { onClosed.run(); } catch (RuntimeException failure) {
            if (first == null) first = failure; else if (failure != first) first.addSuppressed(failure);
        }
        if (first != null) throw first;
    }
}
