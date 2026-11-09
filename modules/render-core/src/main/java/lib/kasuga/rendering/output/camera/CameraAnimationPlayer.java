package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSource;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import lib.kasuga.rendering.output.WorldCameraView;

import java.util.Objects;
import java.util.function.Supplier;

/** Camera write adapter over a private animation Scope. Operations belong to the render thread. */
public final class CameraAnimationPlayer {
    private Supplier<WorldCameraView> provider;
    private final Runnable checkAccess;
    private final AnimationSource<CameraAnimationClip, CameraPose> source;
    private final ScopedCameraPosePlayback playback = new ScopedCameraPosePlayback();

    public CameraAnimationPlayer(Supplier<WorldCameraView> provider) { this(provider, () -> {}); }

    CameraAnimationPlayer(Supplier<WorldCameraView> provider, Runnable checkAccess) {
        this(provider, checkAccess, CameraClipSampler.INSTANCE);
    }

    CameraAnimationPlayer(
            Supplier<WorldCameraView> provider,
            Runnable checkAccess,
            AnimationSource<CameraAnimationClip, CameraPose> source
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.checkAccess = Objects.requireNonNull(checkAccess, "checkAccess");
        this.source = Objects.requireNonNull(source, "source");
    }

    void updatePose(Supplier<WorldCameraView> provider) {
        checkAccess.run();
        this.provider = Objects.requireNonNull(provider, "provider");
        playback.stop();
    }

    void detach() { playback.stop(); provider = null; }

    /** Standalone playback owns its clock. Missing channels retain the live pose provider's values. */
    public void play(CameraAnimationClip clip, boolean loop) {
        checkAccess.run(); playback.play(source, clip, loop);
    }

    public void play(AnimationClip clip, String camera, boolean loop) {
        checkAccess.run(); play(CameraAnimationClip.from(clip, camera), loop);
    }

    /** Follow an external timeline shared with models or other cameras; camera ticks will not advance it. */
    public void follow(CameraAnimationClip clip, AnimationTimeline timeline) {
        checkAccess.run();
        if (playback.ownedTimeline() == Objects.requireNonNull(timeline, "timeline")) {
            throw new IllegalArgumentException(
                    "Cannot follow this camera's owned clock; use an external timeline");
        }
        playback.follow(source, clip, timeline);
    }

    public void follow(AnimationClip clip, String camera, AnimationTimeline timeline) {
        checkAccess.run(); follow(CameraAnimationClip.from(clip, camera), timeline);
    }

    /** Detach only this target. An external shared clock and its other targets keep playing. */
    public void stop() { checkAccess.run(); playback.stop(); }
    public void pause() { checkAccess.run(); playback.pause(); }
    public void resume() { checkAccess.run(); playback.resume(); }
    public void seek(float seconds) { checkAccess.run(); playback.seek(seconds); }
    public void setSpeed(float speed) { checkAccess.run(); playback.setSpeed(speed); }
    public boolean isPlaying() { checkAccess.run(); return playback.isPlaying(); }
    public double currentTime() { checkAccess.run(); return playback.currentTime(); }
    public CameraAnimationClip currentClip() { checkAccess.run(); return playback.currentData(); }
    public AnimationTimeline timeline() { checkAccess.run(); return playback.timeline(); }

    public void tick(float dt) {
        checkAccess.run();
        if (!Float.isFinite(dt) || dt < 0) throw new IllegalArgumentException("Delta time must be finite and non-negative");
        playback.tick(dt);
    }

    /** Sampling never advances the clock. Stopped/detached targets return their underlying provider. */
    public WorldCameraView sample(float partialTick) {
        checkAccess.run();
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Partial tick must be finite");
        var base = Objects.requireNonNull(provider.get(), "Camera provider returned null");
        var pose = playback.sample(partialTick);
        return pose == null ? base : pose.apply(base);
    }
}
