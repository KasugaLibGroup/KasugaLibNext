package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
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
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TimelineClock;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;

import java.util.Objects;
import java.util.UUID;

/**
 * Private Scope adapter for one {@link AnimationPlayer}.
 *
 * <p>The Scope is the sole logical clock owner. Content is sampled during logical publication;
 * render sampling only interpolates the committed pose batch.
 */
final class ScopedPlayerPosePlayback<T> {

    private static final double FIXED_STEP_SECONDS = 0.05;
    private static final int MAX_CATCH_UP_STEPS = 4096;
    private static final double PLACEMENT_DURATION = 1.0e12;
    private static final double STATIC_SOURCE_DURATION = 1.0;

    private final AnimationTimeline timeline = AnimationTimeline.owned();
    private volatile ModelInstance currentModel;

    private final ScopedPlaybackSession<ScopedPlayerPoseTarget, ScopedPoseEvaluator<T>> session =
            new ScopedPlaybackSession<>();
    private volatile AnimationSampler<T> sampler;
    private volatile T data;
    private volatile boolean active;
    private volatile boolean playing;
    private volatile float duration;
    private volatile float speed = 1.0f;
    private volatile boolean loop;
    private volatile double previousTime;
    private volatile double currentTime;

    private final ExternalTimelineBinding external = new ExternalTimelineBinding();

    private long frameSequence;

    ScopedPlayerPosePlayback(ModelInstance initialModel) {
        this.currentModel = Objects.requireNonNull(initialModel, "initialModel");
    }

    boolean isActive() {
        return active;
    }

    synchronized void play(AnimationSampler<T> sampler, T data, boolean loop) {
        float duration = validatedDuration(sampler, data);
        external.clear();
        replace(Objects.requireNonNull(sampler, "sampler"), data, duration, loop, 1.0f,
                0.0, true);
    }

    synchronized void follow(AnimationSampler<T> sampler, T data, AnimationTimeline timeline) {
        float duration = validatedDuration(sampler, data);
        Objects.requireNonNull(timeline, "timeline");
        stop();
        this.sampler = sampler;
        this.data = data;
        this.duration = duration;
        active = true;
        try {
            external.subscribe(timeline, snapshot -> acceptExternal(sampler, data, snapshot));
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
        acceptExternal(sampler, data, timeline.snapshot());
    }

    synchronized void tick(float deltaSeconds) {
        if (external.timeline() != null) {
            return;
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            return;
        }
        if (!playing) {
            previousTime = currentTime;
            updateTimeline();
            return;
        }
        ManagedAnimationScope currentScope = session.scope();
        if (currentScope == null) {
            return;
        }
        previousTime = currentTime;
        ScopedPlayerPoseTarget currentTarget = session.target();
        if (currentTarget != null) {
            currentTarget.beginHostAdvance(previousTime);
        }
        if (!ScopedPlaybackSupport.advance(currentScope, deltaSeconds)) {
            return;
        }
        commitClock(deltaSeconds);
        updateTimeline();
    }

    private void commitClock(float deltaSeconds) {
        double advanced = (double) deltaSeconds * speed;
        double nextTime = currentTime + advanced;
        currentTime = loop ? nextTime : Math.min(nextTime, duration);
        if (!loop && currentTime >= duration) {
            playing = false;
        }
    }

    synchronized void sample(float partialTick) {
        if (!active) {
            return;
        }
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("Partial tick must be finite");
        }
        ManagedAnimationScope currentScope = session.scope();
        if (currentScope == null) {
            return;
        }
        AnimationTimeline externalTimeline = external.timeline();
        AnimationTimeline.Snapshot externalSnapshot = external.snapshot();
        if (externalTimeline != null && externalSnapshot == null) {
            return;
        }
        double alpha = Math.clamp(partialTick, 0.0f, 1.0f);
        double from = externalTimeline != null
                ? externalSnapshot.previous() : previousTime;
        double to = externalTimeline != null
                ? externalSnapshot.seconds() : currentTime;
        double renderTime = from + (to - from) * alpha;
        AnimationRenderPort port = currentScope.renderPort();
        port.sample(new RenderSampleContext(
                new RenderFrameToken(++frameSequence),
                alpha,
                FrameInputSnapshot.empty().with(
                        ScopedPlayerPoseTarget.RENDER_TIME,
                        renderTime)));
    }

    synchronized void pause() {
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.pause();
            return;
        }
        ManagedAnimationScope currentScope = session.scope();
        if (!active || !playing || currentScope == null) {
            return;
        }
        currentScope.pause();
        playing = false;
        previousTime = currentTime;
        updateTimeline();
    }

    synchronized void resume() {
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.resume();
            return;
        }
        ManagedAnimationScope currentScope = session.scope();
        if (!active || playing || currentScope == null
                || (!loop && currentTime >= duration)) {
            return;
        }
        currentScope.resume();
        playing = true;
        previousTime = currentTime;
        updateTimeline();
    }

    synchronized void seek(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0.0f) {
            throw new IllegalArgumentException("Seek time must be finite and non-negative");
        }
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.seek(seconds);
            return;
        }
        if (!active) {
            return;
        }
        double requested = loop ? seconds : Math.min(seconds, duration);
        boolean resumes = playing && (loop || requested < duration);
        double previous = requested;
        replace(sampler, data, duration, loop, speed, requested, resumes);
        previousTime = previous;
        currentTime = requested;
        updateTimeline();
    }

    synchronized void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0.0f) {
            throw new IllegalArgumentException("Speed must be finite and non-negative");
        }
        AnimationTimeline externalTimeline = external.timeline();
        if (externalTimeline != null) {
            externalTimeline.setSpeed(speed);
            return;
        }
        if (!active) {
            return;
        }
        double retainedTime = currentTime();
        boolean retainedPlaying = playing;
        replace(sampler, data, duration, loop, speed, retainedTime, retainedPlaying);
        previousTime = retainedTime;
        currentTime = retainedTime;
        updateTimeline();
    }

    void rebind(ModelInstance fresh) {
        synchronized (this) {
            currentModel = Objects.requireNonNull(fresh, "fresh");
            ScopedPlayerPoseTarget currentTarget = session.target();
            if (currentTarget != null) {
                currentTarget.rebind(fresh);
            }
        }
    }

    void retarget(AnimationSampler<T> nextSampler, T nextData) {
        float nextDuration = validatedDuration(nextSampler, nextData);
        synchronized (this) {
            if (!active) {
                return;
            }
            AnimationTimeline externalTimeline = external.timeline();
            if (externalTimeline != null) {
                follow(nextSampler, nextData, externalTimeline);
                return;
            }
            double retainedTime = loop ? currentTime() : Math.min(currentTime(), nextDuration);
            boolean retainedPlaying = playing && (loop || retainedTime < nextDuration);
            replace(nextSampler, nextData, nextDuration, loop, speed,
                    retainedTime, retainedPlaying);
        }
    }

    double currentTime() {
        AnimationTimeline externalTimeline = external.timeline();
        AnimationTimeline.Snapshot externalSnapshot = external.snapshot();
        if (externalTimeline != null) {
            return externalSnapshot == null ? 0.0 : externalSnapshot.seconds();
        }
        return loop ? currentTime : Math.min(currentTime, duration);
    }

    T data() {
        return data;
    }

    boolean isPlaying() {
        return external.isPlaying(active, playing);
    }

    AnimationTimeline timeline() {
        return external.effectiveTimeline(timeline, active);
    }

    AnimationTimeline ownedTimeline() {
        return timeline;
    }

    void stop() {
        synchronized (this) {
            external.clear();
            ManagedAnimationScope currentScope = session.scope();
            ScopedPlayerPoseTarget currentTarget = session.target();
            session.clear();
            active = false;
            playing = false;
            sampler = null;
            data = null;
            timeline.stop();
            if (currentTarget != null) {
                currentTarget.clearPose();
            }
            if (currentScope != null) {
                currentScope.close();
            }
        }
    }

    private void closeScope() {
        session.closeScope();
    }

    private synchronized void replace(
            AnimationSampler<T> nextSampler,
            T nextData,
            float nextDuration,
            boolean nextLoop,
            float nextSpeed,
            double initialTime,
            boolean initialPlaying
    ) {
        ManagedAnimationScope previousScope = session.scope();
        ScopedPlayerPoseTarget previousTarget = session.target();
        ModelInstance model = previousTarget == null ? currentModel : previousTarget.model();
        Objects.requireNonNull(model, "model");

        ScopedPlayerPoseTarget nextTarget = new ScopedPlayerPoseTarget(model);
        ScopedPoseEvaluator<T> evaluator =
                new ScopedPoseEvaluator<>(nextSampler, nextData, nextDuration, nextLoop);
        ManagedAnimationScope nextScope = buildScope(
                nextTarget, evaluator, nextDuration, nextLoop, nextSpeed, initialTime);

        session.attach(nextScope, nextTarget, evaluator);
        sampler = nextSampler;
        data = nextData;
        duration = nextDuration;
        loop = nextLoop;
        speed = nextSpeed;
        active = true;
        playing = initialPlaying;

        double initialRoot = initialRootTime(nextDuration, nextSpeed, initialTime);
        nextScope.seek(initialRoot);
        previousTime = initialTime;
        currentTime = initialTime;
        if (!initialPlaying) {
            nextScope.pause();
        }
        updateTimeline();

        if (previousScope != null) {
            if (previousTarget != null && previousTarget != nextTarget) {
                previousTarget.clearPose();
            }
            previousScope.close();
        }
    }

    private void acceptExternal(
            AnimationSampler<T> source,
            T sourceData,
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
            var target = session.target();
            ModelInstance model = target == null ? currentModel : target.model();
            closeScope();
            ScopedPlayerPoseTarget nextTarget = new ScopedPlayerPoseTarget(model);
            ScopedPoseEvaluator<T> nextEvaluator =
                    new ScopedPoseEvaluator<>(source, sourceData, duration, snapshot.loop());
            nextEvaluator.clock.configure(snapshot.duration(), snapshot.loop());
            session.attach(
                    buildScope(nextTarget, nextEvaluator, duration, snapshot.loop(), 1.0f, 0.0),
                    nextTarget,
                    nextEvaluator);
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
        var target = session.target();
        if (discontinuity) {
            target.resetHistory();
            session.scope().seek(snapshot.seconds());
            return;
        }
        target.beginHostAdvance(ScopedPlaybackSupport.retainedStart(snapshot));
        double delta = snapshot.seconds() - snapshot.previous();
        if (delta > 1.0e-9) {
            ScopedPlaybackSupport.advance(session.scope(), (float) delta);
        }
    }

    private ManagedAnimationScope buildScope(
            ScopedPlayerPoseTarget nextTarget,
            ScopedPoseEvaluator<T> nextEvaluator,
            float clipDuration,
            boolean nextLoop,
            float bindingSpeed,
            double initialTime
    ) {
        return ManagedAnimationScope.builder(
                        UUID.randomUUID(),
                        LogicalStepPolicy.fixed(FIXED_STEP_SECONDS, MAX_CATCH_UP_STEPS))
                .rootPlayback(
                        "model-pose",
                        binding(clipDuration, nextLoop, bindingSpeed, initialTime))
                .target(new TargetBinding<>(
                        nextTarget,
                        nextEvaluator,
                        new TargetPublicationFactory<>()))
                .build();
    }

    private PlaybackBinding binding(
            float clipDuration,
            boolean loop,
            float speed,
            double initialTime
    ) {
        double sourceDuration = clipDuration == 0.0f
                ? STATIC_SOURCE_DURATION : clipDuration;
        double sourceStart = speed == 0.0f ? initialTime : 0.0;
        return new PlaybackBinding(
                0.0,
                PLACEMENT_DURATION,
                sourceStart,
                sourceDuration,
                speed,
                loop ? PlaybackBoundaryPolicy.REPEAT : PlaybackBoundaryPolicy.HOLD_LAST);
    }

    private static double initialRootTime(
            float clipDuration,
            float speed,
            double initialTime
    ) {
        if (speed == 0.0f || clipDuration == 0.0f) {
            return 0.0;
        }
        return initialTime / speed;
    }

    private void updateTimeline() {
        timeline.mirrorOwned(
                previousTime,
                currentTime,
                duration,
                speed,
                loop,
                playing);
    }

    private static <T> float validatedDuration(AnimationSampler<T> sampler, T data) {
        if (sampler == null || data == null) {
            throw new IllegalArgumentException("Animation source and data are required");
        }
        return ScopedPlaybackSupport.validatedDuration(sampler.duration(data));
    }

    private static final class ScopedPoseEvaluator<T>
            implements lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetOutputEvaluator<
            ScopedPlayerPoseTarget.Output> {

        private final AnimationSampler<T> sampler;
        private final T data;
        private final float duration;
        private final TimelineClock clock;

        private ScopedPoseEvaluator(
                AnimationSampler<T> sampler,
                T data,
                float duration,
                boolean loop
        ) {
            this.sampler = sampler;
            this.data = data;
            this.duration = duration;
            this.clock = new TimelineClock(duration, loop);
        }

        @Override
        public ScopedPlayerPoseTarget.Output evaluate(TargetEvaluationContext context) {
            var samples = LoopAwareSampleTimes.map(
                    context,
                    clock.duration(),
                    clock.loop(),
                    duration,
                    time -> sampleAt(time.rawTime(), time.sourceTime()));
            return samples.isEmpty() ? null : new ScopedPlayerPoseTarget.Output(samples);
        }

        private ScopedPlayerPoseTarget.TimedPose sampleAt(double rawTime, double sourceTime) {
            float sampleTime = (float) sourceTime;
            Pose pose = sampler.sample(data, sampleTime);
            return new ScopedPlayerPoseTarget.TimedPose(
                    rawTime, pose == null ? Pose.empty() : pose);
        }
    }
}
