package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Derives committed source-sample times, including both sides of each loop boundary. */
public final class LoopAwareSampleTimes {

    private LoopAwareSampleTimes() {}

    public static <T> List<T> map(
            TargetEvaluationContext context,
            double clockDuration,
            boolean loop,
            double sourceDuration,
            Function<SampleTime, T> mapper
    ) {
        Objects.requireNonNull(mapper, "mapper");
        return forStep(context, clockDuration, loop, sourceDuration).stream()
                .map(mapper)
                .toList();
    }

    public static List<SampleTime> forStep(
            TargetEvaluationContext context,
            double clockDuration,
            boolean loop,
            double sourceDuration
    ) {
        Objects.requireNonNull(context, "context");
        requireNonNegative(clockDuration, "clockDuration");
        requireNonNegative(sourceDuration, "sourceDuration");
        if (context.playbackTimes().isEmpty()) {
            return List.of();
        }

        PlaybackTimeResolution times = context.playbackTimes().getFirst();
        List<SampleTime> samples = new ArrayList<>();
        if (context.timeStep().discontinuity() != TemporalDiscontinuity.NONE) {
            samples.add(sampleAt(times.currentUnwrappedSourceTime(), clockDuration, loop, sourceDuration));
            return List.copyOf(samples);
        }

        double previous = times.previousUnwrappedSourceTime();
        double current = times.currentUnwrappedSourceTime();
        samples.add(sampleAt(previous, clockDuration, loop, sourceDuration));
        if (loop && clockDuration > 0.0 && current > previous) {
            double boundary = (Math.floor(previous / clockDuration) + 1.0) * clockDuration;
            if (boundary <= current) {
                samples.add(new SampleTime(boundary, Math.min(clockDuration, sourceDuration)));
                samples.add(new SampleTime(boundary, 0.0));
            }
        }
        samples.add(sampleAt(current, clockDuration, loop, sourceDuration));
        return List.copyOf(samples);
    }

    private static SampleTime sampleAt(
            double rawTime,
            double clockDuration,
            boolean loop,
            double sourceDuration
    ) {
        double normalized = loop && clockDuration > 0.0
                ? rawTime % clockDuration
                : Math.min(rawTime, clockDuration);
        return new SampleTime(rawTime, Math.min(normalized, sourceDuration));
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    public record SampleTime(double rawTime, double sourceTime) {
        public SampleTime {
            requireNonNegative(rawTime, "rawTime");
            requireNonNegative(sourceTime, "sourceTime");
        }
    }
}
