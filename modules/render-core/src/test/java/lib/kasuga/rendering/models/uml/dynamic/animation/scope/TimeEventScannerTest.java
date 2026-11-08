package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeEventScannerTest {

    private static final UUID SCOPE_ID = UUID.fromString("5f371c4e-94bf-4ab5-bb77-aef8dd998b4e");
    private static final PlaybackInstanceId INSTANCE = new PlaybackInstanceId(SCOPE_ID, 1);
    private static final TimelineEventDefinition CLICK =
            new TimelineEventDefinition("click", 0.5);

    @Test
    void aNormalIntervalUsesAHalfOpenRange() {
        TimeEventScanner scanner = new TimeEventScanner();
        PlaybackBinding binding = binding(1.0, PlaybackBoundaryPolicy.STOP);

        List<PlaybackEvent> atStart = scanner.scan(
                resolution(0.5, 1.0, 0.5, 1.0, 0, false),
                binding, List.of(CLICK), EventScanPolicy.NORMAL);
        List<PlaybackEvent> atEnd = scanner.scan(
                resolution(1.0, 1.5, 1.0, 1.5, 0, false),
                binding, List.of(CLICK), EventScanPolicy.NORMAL);

        assertEquals(List.of("click"), atStart.stream().map(PlaybackEvent::eventId).toList());
        assertTrue(atEnd.isEmpty());
    }

    @Test
    void aRepeatedSourceEmitsEveryCrossedOccurrence() {
        TimeEventScanner scanner = new TimeEventScanner();
        PlaybackBinding binding = binding(1.0, PlaybackBoundaryPolicy.REPEAT);

        List<PlaybackEvent> events = scanner.scan(
                resolution(0.75, 2.75, 0.75, 0.75, 2, false),
                binding, List.of(CLICK), EventScanPolicy.NORMAL);

        assertEquals(2, events.size());
        assertEquals(List.of(2L, 3L),
                events.stream().map(PlaybackEvent::occurrenceSequence).toList());
    }

    @Test
    void seekSkipsEventsByDefaultAndReplayCanRestoreThem() {
        TimeEventScanner scanner = new TimeEventScanner();
        PlaybackBinding binding = binding(1.0, PlaybackBoundaryPolicy.STOP);
        PlaybackTimeResolution jump = resolution(0.0, 1.0, 0.0, 1.0, 0, true);

        assertTrue(scanner.scan(jump, binding, List.of(CLICK), EventScanPolicy.SKIP).isEmpty());
        assertEquals(1, scanner.scan(
                jump, binding, List.of(CLICK), EventScanPolicy.REPLAY).size());
    }

    @Test
    void pausedAndZeroLengthIntervalsDoNotRepeatAnEvent() {
        TimeEventScanner scanner = new TimeEventScanner();
        PlaybackBinding binding = binding(1.0, PlaybackBoundaryPolicy.STOP);
        PlaybackTimeResolution paused = resolution(0.5, 0.5, 0.5, 0.5, 0, false);

        assertTrue(scanner.scan(paused, binding, List.of(CLICK), EventScanPolicy.NORMAL).isEmpty());
    }

    private static PlaybackBinding binding(double duration, PlaybackBoundaryPolicy policy) {
        return new PlaybackBinding(0.0, 10.0, 0.0, duration, 1.0, policy);
    }

    private static PlaybackTimeResolution resolution(
            double previousUnwrapped,
            double currentUnwrapped,
            double previousLocal,
            double currentLocal,
            long loopCrossings,
            boolean discontinuity
    ) {
        return new PlaybackTimeResolution(
                INSTANCE,
                "clip",
                Optional.empty(),
                previousLocal,
                currentLocal,
                previousLocal,
                currentLocal,
                previousUnwrapped,
                currentUnwrapped,
                true,
                loopCrossings,
                1,
                false
        );
    }
}
