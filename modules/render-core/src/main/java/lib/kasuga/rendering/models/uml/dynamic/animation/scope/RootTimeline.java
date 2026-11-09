package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the authoritative root logical time for one animation scope.
 *
 * <p>The timeline is platform-independent. A platform adapter supplies the step policy and raw
 * deltas; this class alone converts those inputs into logical steps. Plans are staged so a failed
 * scope transaction can discard them without changing authoritative time.
 */
public final class RootTimeline {

    /**
     * Callers expose delta time as float in several adapters. Four 60 Hz deltas can therefore
     * fall a few nanoseconds below the nominal fixed-step boundary even though they represent the
     * same interval. Keep the tolerance well below one rendered millisecond.
     */
    private static final double STEP_EPSILON = 1.0e-6;

    private final LogicalStepPolicy policy;
    private RootTimelineSnapshot snapshot;

    public RootTimeline(LogicalStepPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.snapshot = new RootTimelineSnapshot(0.0, 0.0, 0.0, false, false);
    }

    public RootTimelineSnapshot snapshot() {
        return snapshot;
    }

    public RootTimelinePlan planAdvance(ScopeAdvance advance) {
        Objects.requireNonNull(advance, "advance");
        requireOpen();
        if (snapshot.paused()) {
            return unchangedPlan();
        }

        double total = snapshot.accumulatorSeconds() + advance.rawDeltaSeconds();
        int requestedSteps = requestedStepCount(total);
        int emittedSteps = Math.min(requestedSteps, policy.maxCatchUpSteps());
        int droppedSteps = requestedSteps - emittedSteps;

        List<TemporalStep> steps = new ArrayList<>(emittedSteps);
        double previous = snapshot.currentTime();
        double current = previous;
        for (int index = 0; index < emittedSteps; index++) {
            double next = current + policy.fixedStepSeconds();
            steps.add(new TemporalStep(
                    current, next, policy.fixedStepSeconds(), advance.cause(),
                    TemporalDiscontinuity.NONE));
            previous = current;
            current = next;
        }

        double accumulator = droppedSteps == 0
                ? Math.max(0.0, total - emittedSteps * policy.fixedStepSeconds())
                : 0.0;
        RootTimelineSnapshot staged = new RootTimelineSnapshot(
                previous, current, accumulator, false, false);
        return new RootTimelinePlan(steps, staged, droppedSteps);
    }

    public RootTimelinePlan planPause() {
        requireOpen();
        if (snapshot.paused()) {
            return unchangedPlan();
        }
        return controlPlan(ScopeAdvanceCause.PAUSE, true);
    }

    public RootTimelinePlan planResume() {
        requireOpen();
        if (!snapshot.paused()) {
            return unchangedPlan();
        }
        return controlPlan(ScopeAdvanceCause.RESUME, false);
    }

    public RootTimelinePlan planSeek(double targetTime) {
        requireOpen();
        if (!Double.isFinite(targetTime) || targetTime < 0.0) {
            throw new IllegalArgumentException("targetTime must be finite and non-negative");
        }
        TemporalStep step = new TemporalStep(
                snapshot.currentTime(), targetTime, targetTime - snapshot.currentTime(),
                ScopeAdvanceCause.SEEK, TemporalDiscontinuity.SEEK);
        RootTimelineSnapshot staged = new RootTimelineSnapshot(
                targetTime, targetTime, 0.0, snapshot.paused(), false);
        return new RootTimelinePlan(List.of(step), staged, 0);
    }

    public void commit(RootTimelinePlan plan) {
        Objects.requireNonNull(plan, "plan");
        requireOpen();
        snapshot = plan.snapshot();
    }

    public void close() {
        snapshot = new RootTimelineSnapshot(
                snapshot.previousTime(), snapshot.currentTime(), snapshot.accumulatorSeconds(),
                false, true);
    }

    private RootTimelinePlan controlPlan(ScopeAdvanceCause cause, boolean paused) {
        TemporalStep step = new TemporalStep(
                snapshot.currentTime(), snapshot.currentTime(), 0.0,
                cause, TemporalDiscontinuity.CONTROL);
        RootTimelineSnapshot staged = new RootTimelineSnapshot(
                snapshot.currentTime(), snapshot.currentTime(), snapshot.accumulatorSeconds(),
                paused, false);
        return new RootTimelinePlan(List.of(step), staged, 0);
    }

    private RootTimelinePlan unchangedPlan() {
        return new RootTimelinePlan(List.of(), snapshot, 0);
    }

    private int requestedStepCount(double availableSeconds) {
        return (int) Math.floor((availableSeconds + STEP_EPSILON) / policy.fixedStepSeconds());
    }

    private void requireOpen() {
        if (snapshot.closed()) {
            throw new IllegalStateException("root timeline is closed");
        }
    }
}
