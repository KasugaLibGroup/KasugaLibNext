package lib.kasuga.rendering.models.uml.backend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FrameResourceCacheTest {
    @Test
    void emptyMaskPassDoesNotDeleteOpaqueBufferOrUploadItAgainNextFrame() {
        List<Integer> retired = new ArrayList<>();
        AtomicInteger fullUploads = new AtomicInteger();
        try (var cache = new FrameResourceCache<String, Integer>(retired::add)) {
            for (int frame = 0; frame < 3; frame++) {
                cache.beginFrame();
                assertEquals(1, cache.acquire("opaque", fullUploads::incrementAndGet));
                // No MASK resource is acquired, just as in an empty MASK flush.
                cache.endFrame();
                assertEquals(List.of(), retired);
            }
            assertEquals(1, fullUploads.get());
        }
        assertEquals(List.of(1), retired);
    }

    @Test
    void opaqueAndMaskSurviveBothPassesAndRetireOnlyWhenAbsentForAWholeFrame() {
        List<String> retired = new ArrayList<>();
        var cache = new FrameResourceCache<String, String>(retired::add);
        cache.beginFrame();
        assertEquals("opaque-buffer", cache.acquire("opaque", () -> "opaque-buffer"));
        assertEquals("mask-buffer", cache.acquire("mask", () -> "mask-buffer"));
        cache.endFrame();
        cache.beginFrame();
        assertEquals("opaque-buffer", cache.acquire("opaque", () -> fail("unexpected full upload")));
        assertEquals("mask-buffer", cache.acquire("mask", () -> fail("unexpected full upload")));
        assertEquals(List.of(), retired);
        cache.endFrame();
        cache.beginFrame();
        cache.acquire("mask", () -> fail("unexpected full upload"));
        assertEquals(List.of(), retired);
        cache.endFrame();
        assertEquals(List.of("opaque-buffer"), retired);
        cache.beginFrame();
        cache.endFrame();
        assertEquals(List.of("opaque-buffer", "mask-buffer"), retired);
        cache.close();
        assertEquals(2, retired.size());
    }

    @Test
    void interruptedFrameCanRestartAndFactoryFailureDoesNotPublishAResource() {
        List<String> retired = new ArrayList<>();
        try (var cache = new FrameResourceCache<String, String>(retired::add)) {
            assertThrows(IllegalStateException.class, () -> cache.acquire("a", () -> "a"));
            cache.beginFrame();
            cache.acquire("a", () -> "a");
            assertThrows(IllegalArgumentException.class,
                    () -> cache.acquire("b", () -> { throw new IllegalArgumentException("failed allocation"); }));
            cache.beginFrame();
            assertEquals("a", cache.acquire("a", () -> fail("resource lost during interrupted frame")));
            cache.endFrame();
            assertEquals(1, cache.size());
            assertEquals(List.of(), retired);
        }
        assertEquals(List.of("a"), retired);
    }

    @Test
    void releaseFailureStillRetiresTheOtherResourcesExactlyOnce() {
        List<String> retired = new ArrayList<>();
        var cache = new FrameResourceCache<String, String>(value -> {
            retired.add(value);
            throw new IllegalStateException(value);
        });
        cache.beginFrame();
        cache.acquire("a", () -> "a");
        cache.acquire("b", () -> "b");
        var failure = assertThrows(IllegalStateException.class, cache::close);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("a", "b"), retired);
        assertEquals(0, cache.size());
        cache.close();
        assertEquals(2, retired.size());
    }
}
