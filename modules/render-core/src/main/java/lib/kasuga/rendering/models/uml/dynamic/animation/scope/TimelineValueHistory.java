package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded-by-window committed values used to resolve render interpolation by logical time. */
public final class TimelineValueHistory<T> {

    private static final double TIME_EPSILON = 1.0e-6;

    private final List<TimedValue<T>> values = new ArrayList<>();
    private RenderFrameToken lastFrame;

    public void append(List<TimedValue<T>> samples) {
        Objects.requireNonNull(samples, "samples");
        for (TimedValue<T> sample : samples) {
            if (values.isEmpty() || !values.getLast().equals(sample)) {
                values.add(sample);
            }
        }
    }

    public void retainFrom(double time) {
        values.removeIf(entry -> entry.time() < time - TIME_EPSILON);
    }

    public T resolve(double time, Blend<T> blend) {
        Objects.requireNonNull(blend, "blend");
        if (values.isEmpty()) {
            return null;
        }
        TimedValue<T> first = values.getFirst();
        if (time < first.time() - TIME_EPSILON) {
            return first.value();
        }
        T exact = exactValue(time);
        if (exact != null) {
            return exact;
        }
        T interpolated = interpolate(time, blend);
        return interpolated == null ? values.getLast().value() : interpolated;
    }

    private T exactValue(double time) {
        T exact = null;
        for (TimedValue<T> candidate : values) {
            if (Math.abs(candidate.time() - time) <= TIME_EPSILON) {
                exact = candidate.value();
            }
        }
        return exact;
    }

    private T interpolate(double time, Blend<T> blend) {
        for (int index = 1; index < values.size(); index++) {
            TimedValue<T> left = values.get(index - 1);
            TimedValue<T> right = values.get(index);
            if (left.time() <= time && time <= right.time()) {
                double span = right.time() - left.time();
                float alpha = span == 0.0
                        ? 1.0f : (float) ((time - left.time()) / span);
                return blend.between(left.value(), right.value(), alpha);
            }
        }
        return null;
    }

    public void clear() {
        values.clear();
        lastFrame = null;
    }

    public void clearWith(Runnable additionalReset) {
        Objects.requireNonNull(additionalReset, "additionalReset");
        clear();
        additionalReset.run();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public boolean beginFrame(RenderFrameToken frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.equals(lastFrame)) {
            return false;
        }
        lastFrame = frame;
        return true;
    }

    public Optional<T> resolveFrame(
            RenderSampleContext context,
            FrameInputKey<Double> timeKey,
            String missingTimeMessage,
            Blend<T> blend
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(timeKey, "timeKey");
        if (isEmpty() || !beginFrame(context.frameToken())) {
            return Optional.empty();
        }
        double time = context.frameInputs().value(timeKey).orElseThrow(
                () -> new IllegalArgumentException(missingTimeMessage));
        return Optional.ofNullable(resolve(time, blend));
    }

    @FunctionalInterface
    public interface Blend<T> {
        T between(T left, T right, float alpha);
    }

    public record TimedValue<T>(double time, T value) {
        public TimedValue {
            if (!Double.isFinite(time) || time < 0.0) {
                throw new IllegalArgumentException("time must be finite and non-negative");
            }
            Objects.requireNonNull(value, "value");
        }
    }
}
