package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Scans immutable playback intervals for timeline events. It never executes event handlers. */
public final class TimeEventScanner {

    private static final double EPSILON = 1.0e-9;

    public List<PlaybackEvent> scan(
            PlaybackTimeResolution resolution,
            PlaybackBinding binding,
            List<TimelineEventDefinition> definitions,
            EventScanPolicy policy
    ) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(policy, "policy");
        if (!resolution.active() || policy == EventScanPolicy.SKIP) {
            return List.of();
        }

        double lower = Math.max(binding.sourceStart(), resolution.previousUnwrappedSourceTime());
        double upper = switch (binding.boundaryPolicy()) {
            case STOP, HOLD_LAST ->
                    Math.min(binding.sourceEnd(), resolution.currentUnwrappedSourceTime());
            case REPEAT, PING_PONG -> resolution.currentUnwrappedSourceTime();
        };
        if (upper <= lower + EPSILON) {
            return List.of();
        }

        List<PlaybackEvent> events = new ArrayList<>();
        for (TimelineEventDefinition definition : definitions) {
            double sourceTime = binding.sourceStart() + definition.sourceOffsetSeconds();
            if (sourceTime < binding.sourceStart() - EPSILON
                    || sourceTime >= binding.sourceEnd() - EPSILON) {
                continue;
            }
            long first = firstOccurrence(sourceTime, binding.sourceDuration(), lower);
            double occurrenceTime = sourceTime + first * binding.sourceDuration();
            while (occurrenceTime < upper - EPSILON) {
                events.add(new PlaybackEvent(
                        resolution.instanceId(),
                        definition.eventId(),
                        Math.addExact(first, 1L)));
                first++;
                occurrenceTime = sourceTime + first * binding.sourceDuration();
            }
        }
        events.sort(Comparator.<PlaybackEvent>
                        comparingDouble(event -> occurrenceTime(event, definitions, binding))
                .thenComparing(PlaybackEvent::eventId)
                .thenComparingLong(PlaybackEvent::occurrenceSequence));
        return List.copyOf(events);
    }

    private static long firstOccurrence(double eventTime, double period, double lower) {
        if (lower <= eventTime) {
            return 0L;
        }
        return (long) Math.ceil((lower - eventTime) / period - EPSILON);
    }

    private static double occurrenceTime(
            PlaybackEvent event,
            List<TimelineEventDefinition> definitions,
            PlaybackBinding binding
    ) {
        double offset = definitions.stream()
                .filter(definition -> definition.eventId().equals(event.eventId()))
                .findFirst()
                .orElseThrow()
                .sourceOffsetSeconds();
        return binding.sourceStart() + offset
                + (event.occurrenceSequence() - 1L) * binding.sourceDuration();
    }
}
