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
    }
    public interface Output extends AutoCloseable { boolean isClosed(); }

    private final String viewId;
    private final Producer producer;
    private final Output output;
    private final Runnable checkThread;
    private final Runnable onClosed;
    private Supplier<WorldCameraView> pose;
    private CameraState state = CameraState.READY;
    private Throwable failure;

    public OwnedCamera(String viewId, Supplier<WorldCameraView> pose, Producer producer,
                       Output output, Runnable checkThread, Runnable onClosed) {
        this.viewId = Objects.requireNonNull(viewId);
        this.pose = Objects.requireNonNull(pose);
        this.producer = Objects.requireNonNull(producer);
        this.output = Objects.requireNonNull(output);
        this.checkThread = Objects.requireNonNull(checkThread);
        this.onClosed = Objects.requireNonNull(onClosed);
    }

    public String viewId() { return viewId; }
    public CameraState state() {
        checkThread.run();
        if (state != CameraState.CLOSED && state != CameraState.FAILED && (producer.isClosed() || output.isClosed()))
            fail(producer.failure().orElseGet(() -> new IllegalStateException("Camera producer or output was detached")));
        return state;
    }
    public Optional<Throwable> failure() { checkThread.run(); return Optional.ofNullable(failure); }

    /** Called once by the renderer; providers cannot return a null pose. */
    public WorldCameraView samplePose() {
        checkThread.run();
        ensureUsable();
        try { return Objects.requireNonNull(pose.get(), "Camera provider returned null"); }
        catch (RuntimeException failure) { fail(failure); throw failure; }
    }

    public void updatePose(Supplier<WorldCameraView> pose) {
        checkThread.run(); ensureUsable(); this.pose = Objects.requireNonNull(pose);
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

    private void release() throws Exception {
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
