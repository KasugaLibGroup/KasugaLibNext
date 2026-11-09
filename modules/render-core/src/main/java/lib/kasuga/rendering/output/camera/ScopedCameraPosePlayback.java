package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSource;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ExternalTimelineBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LoopAwareSampleTimes;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackBoundaryPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopedPlaybackSupport;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopedPlaybackSession;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetEvaluationContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetOutputEvaluator;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TimelineClock;

import java.util.Objects;
import java.util.UUID;

/** Private Scope adapter for camera playback and external-timeline following. */
final class ScopedCameraPosePlayback {

    private static final double STEP = 0.05;
    private static final int MAX_CATCH_UP = 4096;
    private static final double PLACEMENT = 1.0e12;

    private final AnimationTimeline mirror = AnimationTimeline.owned();

    private final ScopedPlaybackSession<ScopedCameraPoseTarget, ScopedCameraPoseEvaluator> session =
            new ScopedPlaybackSession<>();
    private volatile AnimationSource<CameraAnimationClip, CameraPose> source;
    private volatile CameraAnimationClip data;
    private volatile boolean active;
    private volatile boolean playing;
    private volatile float duration;
    private volatile float speed = 1.0f;
    private volatile boolean loop;
    private volatile double previousTime;
    private volatile double currentTime;
    private volatile double previousRootTime;
    private volatile double currentRootTime;
    private long frameSequence;

    private final ExternalTimelineBinding external = new ExternalTimelineBinding();

    boolean isActive() {
        return active;
    }

    synchronized void play(AnimationSource<CameraAnimationClip, CameraPose> source,
              CameraAnimationClip clip,
              boolean loop) {
        float duration = validatedDuration(source, clip);
        external.clear();
        replaceStandalone(source, clip, duration, loop, 1.0f, 0.0, true);
    }

    synchronized void follow(AnimationSource<CameraAnimationClip, CameraPose> source,
                CameraAnimationClip clip,
                AnimationTimeline timeline) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(clip, "clip");
        Objects.requireNonNull(timeline, "timeline");
        stop();
        this.source = source;
        data = clip;
        duration = clip.durationSeconds();
        active = true;
        try {
            external.subscribe(timeline, snapshot -> acceptExternal(source, clip, snapshot));
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
        acceptExternal(source, clip, timeline.snapshot());
    }

    synchronized void tick(float deltaSeconds) {
        if (external.timeline() != null) {
            return;
        }
        if (!playing) {
            previousTime = currentTime;
            mirror();
            return;
        }
        ManagedAnimationScope currentScope = session.scope();
        if (currentScope == null) {
            return;
        }
        previousTime = currentTime;
        previousRootTime = currentScope.view().currentTime();
        session.target().beginHostAdvance(previousTime);
        if (!ScopedPlaybackSupport.advance(currentScope, deltaSeconds)) {
            return;
        }
        currentRootTime = currentScope.view().currentTime();
        currentTime = loop ? currentTime + deltaSeconds * speed
                : Math.min(duration, currentTime + deltaSeconds * speed);
        if (!loop && currentTime >= duration) {
            playing = false;
        }
        mirror();
    }

    synchronized CameraPose sample(float partialTick) {
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("Partial tick must be finite");
        }
        ManagedAnimationScope currentScope = session.scope();
        ScopedCameraPoseTarget currentTarget = session.target();
        if (!active || currentScope == null || currentTarget == null) {
            return null;
        }
        double alpha = Math.clamp(partialTick, 0.0f, 1.0f);
        AnimationTimeline externalTimeline = external.timeline();
        AnimationTimeline.Snapshot externalSnapshot = external.snapshot();
        if (externalTimeline != null) {
            if (externalSnapshot == null) {
                return null;
            }
        }
        double from = externalTimeline != null
                ? externalSnapshot.previous() : previousTime;
        double to = externalTimeline != null
                ? externalSnapshot.seconds() : currentTime;
        double renderTime = from + (to - from) * alpha;
        currentScope.renderPort().sample(new RenderSampleContext(
                new RenderFrameToken(++frameSequence),
                alpha,
                FrameInputSnapshot.empty().with(ScopedCameraPoseTarget.RENDER_TIME, renderTime)));
        return currentTarget.sampledPose();
    }

    synchronized void pause() {
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.pause();
        } else if (playing) {
            session.scope().pause();
            playing = false;
            previousTime = currentTime;
            previousRootTime = currentRootTime;
            mirror();
        }
    }

    synchronized void resume() {
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.resume();
        } else if (!playing && (loop || currentTime < duration)) {
            session.scope().resume();
            playing = true;
            previousTime = currentTime;
            previousRootTime = currentRootTime;
            mirror();
        }
    }

    synchronized void seek(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0.0f) {
            throw new IllegalArgumentException("Seek time must be finite and non-negative");
        }
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.seek(seconds);
        } else if (active) {
            double requested = loop ? seconds : Math.min(seconds, duration);
            boolean resumes = playing && (loop || requested < duration);
            replaceStandalone(source, data, duration, loop, speed, requested, resumes);
            previousTime = requested;
            currentTime = requested;
            mirror();
        }
    }

    synchronized void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0.0f) {
            throw new IllegalArgumentException("Speed must be finite and non-negative");
        }
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.setSpeed(speed);
        } else if (active) {
            double retained = currentTime;
            boolean retainedPlaying = playing;
            replaceStandalone(source, data, duration, loop, speed, retained, retainedPlaying);
            previousTime = retained;
            currentTime = retained;
            mirror();
        }
    }

    boolean isPlaying() {
        return external.isPlaying(active, playing);
    }

    double currentTime() {
        AnimationTimeline externalTimeline = external.timeline();
        AnimationTimeline.Snapshot externalSnapshot = external.snapshot();
        return externalTimeline != null
                ? externalSnapshot == null ? 0 : externalSnapshot.seconds()
                : currentTime;
    }

    CameraAnimationClip currentData() {
        return data;
    }

    AnimationTimeline timeline() {
        return external.effectiveTimeline(mirror, active);
    }

    AnimationTimeline ownedTimeline() {
        return mirror;
    }

    synchronized void stop() {
        external.clear();
        ManagedAnimationScope currentScope = session.scope();
        session.clear();
        active = false;
        playing = false;
        data = null;
        source = null;
        mirror.stop();
        if (currentScope != null) {
            currentScope.close();
        }
    }

    private void replaceStandalone(
            AnimationSource<CameraAnimationClip, CameraPose> source,
            CameraAnimationClip clip,
            float clipDuration,
            boolean loop,
            float speed,
            double initialTime,
            boolean initialPlaying
    ) {
        ManagedAnimationScope previousScope = session.scope();
        ScopedCameraPoseTarget nextTarget = new ScopedCameraPoseTarget();
        ScopedCameraPoseEvaluator nextEvaluator =
                new ScopedCameraPoseEvaluator(source, clip, clipDuration, loop);
        ManagedAnimationScope nextScope = buildScope(nextTarget, nextEvaluator, speed, initialTime);
        session.attach(nextScope, nextTarget, nextEvaluator);
        this.source = source;
        data = clip;
        duration = clipDuration;
        this.loop = loop;
        this.speed = speed;
        active = true;
        playing = initialPlaying;
        double initialRoot = speed == 0 || clipDuration == 0 ? 0 : initialTime / speed;
        nextScope.seek(initialRoot);
        previousTime = initialTime;
        currentTime = initialTime;
        previousRootTime = initialRoot;
        currentRootTime = initialRoot;
        if (!initialPlaying) {
            nextScope.pause();
        }
        mirror();
        if (previousScope != null) {
            previousScope.close();
        }
    }

    private synchronized void acceptExternal(
            AnimationSource<CameraAnimationClip, CameraPose> source,
            CameraAnimationClip clip,
            AnimationTimeline.Snapshot snapshot
    ) {
        AnimationTimeline.Snapshot previous = external.swap(snapshot);
        if (snapshot == null) {
            closeScope();
            return;
        }
        var evaluator = session.evaluator();
        boolean rebuild = session.scope() == null
                || evaluator.clock.duration() != snapshot.duration()
                || evaluator.clock.loop() != snapshot.loop();
        if (rebuild) {
            closeScope();
            ScopedCameraPoseTarget nextTarget = new ScopedCameraPoseTarget();
            ScopedCameraPoseEvaluator nextEvaluator =
                    new ScopedCameraPoseEvaluator(source, clip, clip.durationSeconds(), snapshot.loop());
            nextEvaluator.clock.configure(snapshot.duration(), snapshot.loop());
            session.attach(buildScope(nextTarget, nextEvaluator, 1.0f, 0.0f), nextTarget, nextEvaluator);
            evaluator = nextEvaluator;
        }
        evaluator.clock.configure(snapshot.duration(), snapshot.loop());
        synchronizeExternal(previous, snapshot);
    }

    private void synchronizeExternal(
            AnimationTimeline.Snapshot previous,
            AnimationTimeline.Snapshot snapshot
    ) {
        boolean discontinuity = ScopedPlaybackSupport.isDiscontinuity(previous, snapshot);
        if (discontinuity) {
            session.target().resetHistory();
            session.scope().seek(snapshot.seconds());
        } else {
            session.target().beginHostAdvance(ScopedPlaybackSupport.retainedStart(snapshot));
            double delta = snapshot.seconds() - snapshot.previous();
            if (delta > 1.0e-9
                    && !ScopedPlaybackSupport.advance(session.scope(), (float) delta)) {
                return;
            }
        }
    }

    private ManagedAnimationScope buildScope(
            ScopedCameraPoseTarget nextTarget,
            ScopedCameraPoseEvaluator nextEvaluator,
            float bindingSpeed,
            double initialTime
    ) {
        double sourceStart = bindingSpeed == 0 ? initialTime : 0;
        return ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(STEP, MAX_CATCH_UP))
                .rootPlayback("camera-pose", new PlaybackBinding(
                        0, PLACEMENT, sourceStart, 1, bindingSpeed,
                        PlaybackBoundaryPolicy.HOLD_LAST))
                .target(new TargetBinding<>(
                        nextTarget,
                        nextEvaluator,
                        new TargetPublicationFactory<>()))
                .build();
    }

    private void mirror() {
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline == null && active) {
            mirror.mirrorOwned(previousTime, currentTime, duration, speed, loop, playing);
        }
    }

    private void closeScope() {
        session.closeScope();
    }

    private static float validatedDuration(
            AnimationSource<CameraAnimationClip, CameraPose> source,
            CameraAnimationClip clip
    ) {
        if (source == null || clip == null) {
            throw new IllegalArgumentException("Animation source and data are required");
        }
        return ScopedPlaybackSupport.validatedDuration(source.duration(clip));
    }

    private static final class ScopedCameraPoseEvaluator
            implements TargetOutputEvaluator<ScopedCameraPoseTarget.Output> {

        private final AnimationSource<CameraAnimationClip, CameraPose> source;
        private final CameraAnimationClip clip;
        private final float clipDuration;
        private final TimelineClock clock;

        private ScopedCameraPoseEvaluator(
                AnimationSource<CameraAnimationClip, CameraPose> source,
                CameraAnimationClip clip,
                float clipDuration,
                boolean loop
        ) {
            this.source = source;
            this.clip = clip;
            this.clipDuration = clipDuration;
            this.clock = new TimelineClock(clipDuration, loop);
        }

        @Override
        public ScopedCameraPoseTarget.Output evaluate(TargetEvaluationContext context) {
            var samples = LoopAwareSampleTimes.map(
                    context,
                    clock.duration(),
                    clock.loop(),
                    clipDuration,
                    time -> sampleAt(time.rawTime(), time.sourceTime()));
            return samples.isEmpty() ? null : new ScopedCameraPoseTarget.Output(samples);
        }

        private ScopedCameraPoseTarget.TimedPose sampleAt(double rawTime, double sourceTime) {
            float floatTime = (float) sourceTime;
            return new ScopedCameraPoseTarget.TimedPose(
                    rawTime, floatTime, source.sample(clip, floatTime));
        }
    }
}
