package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete transaction coordinator for one animation scope.
 *
 * <p>It owns orchestration only. Logical time, inputs, controller state, playback state and target
 * routing remain in their dedicated collaborators.
 */
public final class ManagedAnimationScope implements AnimationScope {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedAnimationScope.class);

    private final UUID scopeId;
    private final RootTimeline rootTimeline;
    private final ScopeInputStore inputs;
    private final ControllerCoordinator controllers;
    private final PlaybackRegistry playbacks;
    private final PlaybackTimeGraph timeGraph;
    private final TargetRegistry targets;
    private final ScopeSnapshotPublisher publisher;
    private final PlaybackCommandApplier commandApplier;
    private final CommandTransaction commandTransaction;
    private final TimeEventScanner eventScanner = new TimeEventScanner();
    private final Map<PlaybackInstanceId, List<TimelineEventDefinition>> eventDefinitions;
    private final Consumer<ControlEvent> controlEventSink;
    private final PlaybackControlPort playbackPort = new ScopePlaybackControlPort();
    private boolean advancing;
    private boolean closed;

    private ManagedAnimationScope(Builder builder) {
        this.scopeId = builder.scopeId;
        this.rootTimeline = new RootTimeline(builder.policy);
        this.inputs = new ScopeInputStore();
        this.controllers = new ControllerCoordinator(builder.controllerBindings);
        this.playbacks = new PlaybackRegistry(scopeId);
        this.timeGraph = new PlaybackTimeGraph();
        this.targets = new TargetRegistry();
        this.publisher = new ScopeSnapshotPublisher();
        this.commandApplier = builder.commandApplier;
        this.commandTransaction = new CommandTransaction();
        this.controlEventSink = builder.controlEventSink;
        builder.targetBindings.forEach(targets::register);
        Map<PlaybackInstanceId, List<TimelineEventDefinition>> configuredEvents = new HashMap<>();
        for (PlaybackRoot root : builder.playbackRoots) {
            PlaybackInstanceId id = playbacks.addRoot(root.definitionId(), root.binding());
            if (!root.events().isEmpty()) {
                configuredEvents.put(id, root.events());
            }
        }
        this.eventDefinitions = Map.copyOf(configuredEvents);
    }

    public static Builder builder(UUID scopeId, LogicalStepPolicy policy) {
        return new Builder(scopeId, policy);
    }

    @Override
    public synchronized ScopeAdvanceResult advance(ScopeAdvance advance) {
        Objects.requireNonNull(advance, "advance");
        requireOpen();
        if (advancing) {
            throw new IllegalStateException("animation scope is already advancing");
        }
        advancing = true;
        try {
            return execute(rootTimeline.planAdvance(advance));
        } finally {
            advancing = false;
        }
    }

    @Override
    public AnimationInputPort inputPort() {
        return inputs.inputPort();
    }

    @Override
    public PlaybackControlPort playbackPort() {
        return playbackPort;
    }

    /** Returns the narrow target-sampling endpoint exposed to a platform render adapter. */
    public AnimationRenderPort renderPort() {
        return targets;
    }

    @Override
    public synchronized ScopeView view() {
        RootTimelineSnapshot timeline = rootTimeline.snapshot();
        List<PlaybackTimeResolution> times =
                timeGraph.resolve(playbacks, timeline.currentTime(), timeline.currentTime());
        return new ScopeView(
                scopeId,
                timeline.previousTime(),
                timeline.currentTime(),
                publisher.currentRevision(),
                timeline.paused(),
                closed,
                views(times));
    }

    @Override
    public synchronized ScopeAdvanceResult pause() {
        return executeControl(rootTimeline.planPause());
    }

    @Override
    public synchronized ScopeAdvanceResult resume() {
        return executeControl(rootTimeline.planResume());
    }

    @Override
    public synchronized ScopeAdvanceResult seek(double scopeTime) {
        return executeControl(rootTimeline.planSeek(scopeTime));
    }

    @Override
    public boolean isPaused() {
        return rootTimeline.snapshot().paused();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        rootTimeline.close();
        publisher.close();
        targets.close();
    }

    private ScopeAdvanceResult executeControl(RootTimelinePlan plan) {
        requireOpen();
        if (advancing) {
            throw new IllegalStateException("animation scope is already advancing");
        }
        advancing = true;
        try {
            return execute(plan);
        } finally {
            advancing = false;
        }
    }

    private ScopeAdvanceResult execute(RootTimelinePlan plan) {
        long firstRevision = publisher.currentRevision();
        long lastRevision = firstRevision;
        List<TemporalStep> publishedSteps = new ArrayList<>(plan.steps().size());

        for (int index = 0; index < plan.steps().size(); index++) {
            TemporalStep step = plan.steps().get(index);
            ScopeInputSnapshot inputSnapshot = inputs.freeze();
            List<PlaybackTimeResolution> preStepTimes =
                    timeGraph.resolve(playbacks, step.previousTime(), step.currentTime());
            List<PlaybackView> preStepViews = views(preStepTimes);
            List<PlaybackEvent> playbackEvents =
                    scanEvents(step, preStepTimes);

            ControllerRound controllerRound;
            try {
                controllerRound = controllers.evaluate(new ControllerEvaluationContext(
                        step, inputSnapshot, preStepViews, playbackEvents, preStepTimes));
            } catch (RuntimeException failure) {
                return result(
                        publishedSteps, firstRevision, lastRevision, plan, true, failure);
            }
            if (controllerRound.commands().size() > 0 && commandApplier == null) {
                controllerRound.discard();
                return result(
                        publishedSteps,
                        firstRevision,
                        lastRevision,
                        plan,
                        true,
                        new IllegalStateException("playback command applier is not configured"));
            }

            List<CommandEnvelope> envelopes = envelopes(controllerRound.commands(), index);
            CommandCommit commandCommit = commandTransaction.commit(envelopes, commandApplier);
            if (commandCommit.aborted()) {
                controllerRound.discard();
                return result(
                        publishedSteps,
                        firstRevision,
                        lastRevision,
                        plan,
                        true,
                        commandCommit.failure());
            }

            boolean finalStep = index == plan.steps().size() - 1;
            RootTimelineSnapshot stagedTimeline = new RootTimelineSnapshot(
                    step.previousTime(),
                    step.currentTime(),
                    finalStep ? plan.snapshot().accumulatorSeconds() : 0.0,
                    plan.snapshot().paused(),
                    false);
            rootTimeline.commit(new RootTimelinePlan(List.of(step), stagedTimeline, 0));
            inputs.commit(inputSnapshot);
            controllerRound.commit();

            List<PlaybackTimeResolution> postStepTimes =
                    timeGraph.resolve(playbacks, step.previousTime(), step.currentTime());
            ScopeSnapshot snapshot = publisher.publish(
                    step, stagedTimeline, inputSnapshot, postStepTimes);
            if (publishedSteps.isEmpty()) {
                firstRevision = snapshot.revision();
            }
            lastRevision = snapshot.revision();
            publishedSteps.add(step);

            targets.publish(new TargetEvaluationContext(
                    snapshot.revision(),
                    step,
                    stagedTimeline,
                    inputSnapshot,
                    views(postStepTimes),
                    postStepTimes));
            dispatchControlEvents(controllerRound.events());
        }
        if (plan.steps().isEmpty()) {
            rootTimeline.commit(plan);
        }
        return result(publishedSteps, firstRevision, lastRevision, plan, false, null);
    }

    private ScopeAdvanceResult result(
            List<TemporalStep> steps,
            long firstRevision,
            long lastRevision,
            RootTimelinePlan plan,
            boolean aborted,
            RuntimeException failure
    ) {
        RootTimelineSnapshot timeline = rootTimeline.snapshot();
        return new ScopeAdvanceResult(
                scopeId,
                steps,
                firstRevision,
                lastRevision,
                plan.droppedSteps(),
                timeline.paused(),
                closed,
                aborted,
                failure);
    }

    private List<CommandEnvelope> envelopes(List<PlaybackCommand> commands, int stepIndex) {
        List<CommandEnvelope> envelopes = new ArrayList<>(commands.size());
        long candidateRevision = publisher.currentRevision() + 1;
        for (int index = 0; index < commands.size(); index++) {
            envelopes.add(new CommandEnvelope(
                    scopeId + ":" + candidateRevision + ":" + stepIndex + ":" + index,
                    CommandPriority.CONTROLLER,
                    stepIndex,
                    index,
                    commands.get(index)));
        }
        return List.copyOf(envelopes);
    }

    private List<PlaybackView> views(List<PlaybackTimeResolution> times) {
        List<PlaybackView> views = new ArrayList<>(times.size());
        for (PlaybackTimeResolution time : times) {
            PlaybackInstanceState state = playbacks.state(time.instanceId());
            views.add(new PlaybackView(
                    time.instanceId(),
                    time.definitionId(),
                    time.parentId().orElse(null),
                    time.currentSourceLocalTime(),
                    state.binding().speed(),
                    state.paused(),
                    time.active(),
                    time.completed()));
        }
        return List.copyOf(views);
    }

    private List<PlaybackEvent> scanEvents(
            TemporalStep step,
            List<PlaybackTimeResolution> times
    ) {
        if (eventDefinitions.isEmpty()) {
            return List.of();
        }
        EventScanPolicy policy = step.discontinuity() == TemporalDiscontinuity.SEEK
                ? EventScanPolicy.SKIP
                : EventScanPolicy.NORMAL;
        List<PlaybackEvent> events = new ArrayList<>();
        for (PlaybackTimeResolution time : times) {
            List<TimelineEventDefinition> definitions =
                    eventDefinitions.get(time.instanceId());
            if (definitions == null || definitions.isEmpty()) {
                continue;
            }
            events.addAll(eventScanner.scan(
                    time,
                    playbacks.state(time.instanceId()).binding(),
                    definitions,
                    policy));
        }
        return List.copyOf(events);
    }

    private void dispatchControlEvents(List<ControlEvent> events) {
        for (ControlEvent event : events) {
            try {
                controlEventSink.accept(event);
            } catch (RuntimeException failure) {
                LOG.error("Animation control event sink failed for {}", event.typeId(), failure);
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("animation scope is closed");
        }
    }

    public static final class Builder {

        private final UUID scopeId;
        private final LogicalStepPolicy policy;
        private final List<ControllerBinding<?>> controllerBindings = new ArrayList<>();
        private final List<TargetBinding<?>> targetBindings = new ArrayList<>();
        private final List<PlaybackRoot> playbackRoots = new ArrayList<>();
        private PlaybackCommandApplier commandApplier =
                command -> {
                    throw new CommandRejectedException(
                            "no playback command applier configured");
                };
        private Consumer<ControlEvent> controlEventSink = ignored -> {
        };

        private Builder(UUID scopeId, LogicalStepPolicy policy) {
            this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        public Builder controller(ControllerBinding<?> binding) {
            controllerBindings.add(Objects.requireNonNull(binding, "binding"));
            return this;
        }

        public Builder target(TargetBinding<?> binding) {
            targetBindings.add(Objects.requireNonNull(binding, "binding"));
            return this;
        }

        public Builder commandApplier(PlaybackCommandApplier applier) {
            this.commandApplier = Objects.requireNonNull(applier, "applier");
            return this;
        }

        public Builder rootPlayback(String definitionId, PlaybackBinding binding) {
            return rootPlayback(definitionId, binding, List.of());
        }

        public Builder rootPlayback(
                String definitionId,
                PlaybackBinding binding,
                List<TimelineEventDefinition> events
        ) {
            PlaybackRoot root = new PlaybackRoot(
                    Objects.requireNonNull(definitionId, "definitionId"),
                    Objects.requireNonNull(binding, "binding"),
                    List.copyOf(Objects.requireNonNull(events, "events")));
            playbackRoots.add(root);
            return this;
        }

        public Builder controlEventSink(Consumer<ControlEvent> sink) {
            this.controlEventSink = Objects.requireNonNull(sink, "sink");
            return this;
        }

        public ManagedAnimationScope build() {
            return new ManagedAnimationScope(this);
        }
    }

    private record PlaybackRoot(
            String definitionId,
            PlaybackBinding binding,
            List<TimelineEventDefinition> events
    ) {
    }

    private final class ScopePlaybackControlPort implements PlaybackControlPort {

        @Override
        public PlaybackInstanceId addRoot(
                String definitionId,
                PlaybackBinding binding
        ) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                return playbacks.addRoot(definitionId, binding);
            }
        }

        @Override
        public PlaybackInstanceId addChild(
                PlaybackInstanceId parentId,
                String definitionId,
                PlaybackBinding binding
        ) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                return playbacks.addChild(parentId, definitionId, binding);
            }
        }

        @Override
        public void stopSubtree(PlaybackInstanceId rootId) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                playbacks.stopSubtree(rootId);
            }
        }

        @Override
        public void pauseSubtree(PlaybackInstanceId rootId) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                playbacks.pauseSubtree(rootId, rootTimeline.snapshot().currentTime());
            }
        }

        @Override
        public void resumeSubtree(PlaybackInstanceId rootId) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                playbacks.resumeSubtree(rootId, rootTimeline.snapshot().currentTime());
            }
        }

        @Override
        public void setSpeed(PlaybackInstanceId id, double speed) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                playbacks.setSpeed(id, speed);
            }
        }

        @Override
        public void setBoundaryPolicy(
                PlaybackInstanceId id,
                PlaybackBoundaryPolicy policy
        ) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                playbacks.setBoundaryPolicy(id, policy);
            }
        }

        @Override
        public void replace(PlaybackInstanceId id, String definitionId) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                playbacks.replace(id, definitionId);
            }
        }

        @Override
        public PlaybackInstanceState state(PlaybackInstanceId id) {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                return playbacks.state(id);
            }
        }

        @Override
        public List<PlaybackView> views() {
            synchronized (ManagedAnimationScope.this) {
                requireOpen();
                return ManagedAnimationScope.this.views(timeGraph.resolve(
                        playbacks,
                        rootTimeline.snapshot().currentTime(),
                        rootTimeline.snapshot().currentTime()));
            }
        }
    }
}
