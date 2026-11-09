package lib.kasuga.rendering.models.uml.dynamic.animation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationTimelineTest {

    @Test
    void oneFailingObserverDoesNotBlockOtherObservers() throws Exception {
        AnimationTimeline timeline = new AnimationTimeline();
        AtomicInteger observed = new AtomicInteger();
        try (AutoCloseable ignored = timeline.observe(snapshot -> {
            throw new IllegalStateException("broken follower");
        });
             AutoCloseable ignored2 = timeline.observe(snapshot -> observed.incrementAndGet())) {
            timeline.play(1f, false);
            timeline.seek(0.5f);
        }

        assertEquals(2, observed.get());
    }

    @Test
    void closedObserverNoLongerReceivesSnapshots() throws Exception {
        AnimationTimeline timeline = new AnimationTimeline();
        AtomicInteger observed = new AtomicInteger();
        AutoCloseable registration =
                timeline.observe(snapshot -> observed.incrementAndGet());
        timeline.play(1f, false);
        registration.close();
        timeline.seek(0.5f);

        assertEquals(1, observed.get());
    }
}
