package lib.kasuga.rendering.output;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FrameOutputRouterTest {
    @Test
    void dormantOutputsAllocateNothingAndMirrorKeepsPresentation() throws Exception {
        var target = new Target();
        var frames = new ArrayList<OutputFrame<Integer>>();
        try (var router = new FrameOutputRouter<Integer>()) {
            var registration = router.register("main", FrameOutputMode.MIRROR, () -> target, frames::add);
            assertFalse(router.publish("main", 0, 8, (w, h) -> fail()).suppressScreen());
            assertEquals(0, target.captures);
            var delivery = router.publish("main", 16, 8, (w, h) -> {
                assertEquals(16, w); assertEquals(8, h);
            });
            assertEquals(1, delivery.delivered());
            assertFalse(delivery.suppressScreen());
            assertEquals(1L, frames.getFirst().frameNumber());
            assertEquals(16, frames.getFirst().width());
            assertEquals(frames.getFirst(), registration.latest().orElseThrow());
        }
        assertEquals(1, target.closes);
    }

    @Test
    void viewsAreIndependentAndOnlySuccessfulExclusiveOutputsSuppressScreen() throws Exception {
        var main = new Target(); var camera = new Target();
        try (var router = new FrameOutputRouter<Integer>()) {
            router.register("main", FrameOutputMode.MIRROR, () -> main, frame -> {});
            router.register("camera:two", FrameOutputMode.OFFSCREEN_ONLY, () -> camera, frame -> {});
            assertFalse(router.publish("main", 8, 8, (w, h) -> {}).suppressScreen());
            assertEquals(0, camera.captures);
            assertTrue(router.publish("camera:two", 8, 8, (w, h) -> {}).suppressScreen());
            assertEquals(1, main.captures);
            assertEquals(1, camera.captures);
            assertFalse(router.publish("unknown", 8, 8, (w, h) -> fail()).suppressScreen());
        }
    }

    @Test
    void failedConsumerIsDetachedAndOtherOutputsStillReceiveFrames() throws Exception {
        var broken = new Target(); var healthy = new Target();
        try (var router = new FrameOutputRouter<Integer>()) {
            var failed = router.register("main", FrameOutputMode.OFFSCREEN_ONLY, () -> broken,
                    frame -> { throw new IllegalStateException("consumer"); });
            router.register("main", FrameOutputMode.MIRROR, () -> healthy, frame -> {});
            var delivery = router.publish("main", 8, 8, (w, h) -> {});
            assertFalse(delivery.suppressScreen());
            assertEquals(1, delivery.delivered());
            assertEquals(1, delivery.failures().size());
            assertEquals(0, broken.closes);
            assertTrue(failed.isClosed());
            assertTrue(failed.latest().isEmpty());
            router.publish("main", 8, 8, (w, h) -> {});
            assertEquals(2, broken.captures);
            assertEquals(0, healthy.captures);
        }
    }

    @Test
    void failureToCaptureOrReleaseFallsBackAndIsReported() throws Exception {
        var target = new Target(); target.failCapture = target.failClose = true;
        try (var router = new FrameOutputRouter<Integer>()) {
            router.register("main", FrameOutputMode.OFFSCREEN_ONLY, () -> target, frame -> fail());
            var delivery = router.publish("main", 8, 8, (w, h) -> {});
            assertEquals(0, delivery.delivered());
            assertFalse(delivery.suppressScreen());
            assertEquals(2, delivery.failures().size());
            assertFalse(router.hasOutputs("main"));
        }
        assertEquals(1, target.closes);
    }

    @Test
    void closingDuringCallbackDefersReleaseUntilBorrowedFrameReturns() throws Exception {
        var target = new Target();
        try (var router = new FrameOutputRouter<Integer>()) {
            router.register("main", FrameOutputMode.OFFSCREEN_ONLY, () -> target, frame -> {
                try { router.close(); } catch (Exception failure) { throw new RuntimeException(failure); }
                assertEquals(0, target.closes);
            });
            var delivery = router.publish("main", 8, 8, (w, h) -> {});
            assertFalse(delivery.suppressScreen());
            assertEquals(1, target.closes);
            assertThrows(IllegalStateException.class,
                    () -> router.publish("main", 8, 8, (w, h) -> {}));
        }
    }

    @Test
    void registrationInCallbackStartsOnTheNextPublication() throws Exception {
        var first = new Target(); var second = new Target();
        try (var router = new FrameOutputRouter<Integer>()) {
            router.register("main", FrameOutputMode.MIRROR, () -> first, frame -> {
                if (frame.frameNumber() == 1) {
                    router.register("main", FrameOutputMode.MIRROR, () -> second, ignored -> {});
                }
            });
            assertEquals(1, router.publish("main", 8, 8, (w, h) -> {}).delivered());
            assertEquals(2, router.publish("main", 8, 8, (w, h) -> {}).delivered());
            assertEquals(2, first.captures);
            assertEquals(0, second.captures);
        }
    }


    @Test
    void subscribersShareOneFrameAndOnlyLastCloseReleasesStorage() throws Exception {
        var target = new Target();
        var received = new ArrayList<OutputFrame<Integer>>();
        int[] allocations = {0};
        try (var router = new FrameOutputRouter<Integer>(() -> {}, () -> {
            allocations[0]++;
            return target;
        })) {
            var first = router.register("main", FrameOutputMode.OFFSCREEN_ONLY, received::add);
            var second = router.register("main", FrameOutputMode.MIRROR, received::add);
            var third = router.register("main", FrameOutputMode.MIRROR, received::add);
            assertEquals(0, allocations[0]);
            assertEquals(3, router.publish("main", 8, 8, (w, h) -> {}).delivered());
            assertEquals(1, allocations[0]);
            assertEquals(1, target.captures);
            assertSame(received.get(0), received.get(1));
            assertSame(received.get(0), received.get(2));
            first.close();
            assertEquals(0, target.closes);
            assertSame(received.getFirst(), second.latest().orElseThrow());
            assertFalse(router.publish("main", 16, 8, (w, h) -> {}).suppressScreen());
            assertEquals(2, target.captures);
            second.close();
            assertEquals(0, target.closes);
            third.close(); third.close();
            assertEquals(1, target.closes);
            assertFalse(router.hasOutputs("main"));
        }
        assertEquals(1, target.closes);
    }

    @Test
    void everyLatestHandleIsUpdatedBeforeFirstCallbackIncludingResize() throws Exception {
        var target = new Target();
        var peers = new ArrayList<FrameOutputRouter<Integer>.Registration>();
        try (var router = new FrameOutputRouter<Integer>()) {
            router.register("main", FrameOutputMode.MIRROR, () -> target, frame -> {
                assertSame(frame, peers.getFirst().latest().orElseThrow());
            });
            peers.add(router.register("main", FrameOutputMode.MIRROR, () -> {
                fail("Shared consumer must not allocate a target"); return target;
            }, frame -> {}));
            router.publish("main", 8, 8, (w, h) -> {});
            router.publish("main", 16, 8, (w, h) -> {});
        }
    }

    @Test
    void lastSubscriberReplacedInsideCallbackKeepsTheBorrowedTarget() throws Exception {
        var target = new Target();
        var subscriptions = new ArrayList<FrameOutputRouter<Integer>.Registration>();
        try (var router = new FrameOutputRouter<Integer>(() -> {}, () -> target)) {
            subscriptions.add(router.register("main", FrameOutputMode.MIRROR, frame -> {
                try { subscriptions.getFirst().close(); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                subscriptions.add(router.register("main", FrameOutputMode.MIRROR, next -> {}));
                assertEquals(0, target.closes);
                assertTrue(subscriptions.getLast().latest().isEmpty());
            }));
            assertEquals(0, router.publish("main", 8, 8, (w, h) -> {}).delivered());
            assertEquals(0, target.closes);
            assertEquals(1, router.publish("main", 8, 8, (w, h) -> {}).delivered());
            assertEquals(2, target.captures);
        }
        assertEquals(1, target.closes);
    }

    @Test
    void sharedCaptureFailureDetachesAllSubscribersButNotOtherViews() throws Exception {
        var broken = new Target(); broken.failCapture = true;
        var healthy = new Target();
        try (var router = new FrameOutputRouter<Integer>()) {
            var first = router.register("main", FrameOutputMode.OFFSCREEN_ONLY, () -> broken, frame -> fail());
            var second = router.register("main", FrameOutputMode.MIRROR, () -> healthy, frame -> fail());
            router.register("other", FrameOutputMode.MIRROR, () -> healthy, frame -> {});
            var delivery = router.publish("main", 8, 8, (w, h) -> {});
            assertEquals(1, delivery.failures().size());
            assertFalse(delivery.suppressScreen());
            assertTrue(first.isClosed()); assertTrue(second.isClosed());
            assertEquals(1, broken.captures); assertEquals(1, broken.closes);
            assertEquals(0, healthy.captures);
            assertEquals(1, router.publish("other", 8, 8, (w, h) -> {}).delivered());
        }
    }

    @Test
    void closingPeerAfterDeliveryRestoresScreenAndDoesNotLeakTarget() throws Exception {
        var target = new Target();
        var peers = new ArrayList<FrameOutputRouter<Integer>.Registration>();
        try (var router = new FrameOutputRouter<Integer>(() -> {}, () -> target)) {
            peers.add(router.register("main", FrameOutputMode.OFFSCREEN_ONLY, frame -> {}));
            router.register("main", FrameOutputMode.MIRROR, frame -> {
                try { peers.getFirst().close(); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                assertEquals(0, target.closes);
            });
            var delivery = router.publish("main", 8, 8, (w, h) -> {});
            assertEquals(1, delivery.delivered());
            assertFalse(delivery.suppressScreen());
        }
        assertEquals(1, target.closes);
    }

    @Test
    void releaseFailuresFromIndependentViewsAreAggregatedOnce() throws Exception {
        var first = new Target(); first.failClose = true;
        var second = new Target(); second.failClose = true;
        var router = new FrameOutputRouter<Integer>();
        router.register("first", FrameOutputMode.MIRROR, () -> first, frame -> {});
        router.register("first", FrameOutputMode.MIRROR, () -> first, frame -> {});
        router.register("second", FrameOutputMode.MIRROR, () -> second, frame -> {});
        router.publish("first", 8, 8, (w, h) -> {});
        router.publish("second", 8, 8, (w, h) -> {});
        Exception failure = assertThrows(Exception.class, router::close);
        assertEquals(1, failure.getSuppressed().length);
        router.close();
        assertEquals(1, first.closes); assertEquals(1, second.closes);
    }

    private static final class Target implements FrameOutputTarget<Integer> {
        int captures, closes;
        boolean failCapture, failClose;
        public Integer capture(FrameBlitter blitter, int width, int height) {
            captures++;
            if (failCapture) throw new IllegalStateException("capture");
            blitter.draw(width, height);
            return captures;
        }
        public void close() throws Exception {
            closes++;
            if (failClose) throw new Exception("release");
        }
    }
}
