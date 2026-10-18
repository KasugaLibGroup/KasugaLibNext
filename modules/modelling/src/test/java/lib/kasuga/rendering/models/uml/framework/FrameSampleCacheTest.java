package lib.kasuga.rendering.models.uml.framework;

import lib.kasuga.rendering.models.uml.framework.schedule.FrameSampleCache;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameSampleCacheTest {
    @Test
    void samplesOnceAcrossViewsButIncludesNewlyVisibleInstances() {
        FrameSampleCache<Object> cache = new FrameSampleCache<>();
        Object frame = new Object(), frontVisible = new Object(), backVisible = new Object();
        cache.beginFrame(frame);
        assertTrue(cache.firstSample(frontVisible));
        assertFalse(cache.firstSample(frontVisible)); // material pass
        cache.beginFrame(frame); // second camera
        assertFalse(cache.firstSample(frontVisible));
        assertTrue(cache.firstSample(backVisible));
        cache.beginFrame(new Object());
        assertTrue(cache.firstSample(frontVisible));
        assertTrue(cache.firstSample(backVisible));
    }

    @Test
    void identityAndRetirementRemainIndependent() {
        FrameSampleCache<String> cache = new FrameSampleCache<>();
        cache.beginFrame(new Object());
        String a = new String("same"), b = new String("same");
        assertTrue(cache.firstSample(a)); assertTrue(cache.firstSample(b));
        cache.forget(a);
        assertTrue(cache.firstSample(a)); assertFalse(cache.firstSample(b));
        cache.clear(); assertTrue(cache.firstSample(b));
    }
}
