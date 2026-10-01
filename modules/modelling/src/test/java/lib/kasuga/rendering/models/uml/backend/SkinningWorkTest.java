package lib.kasuga.rendering.models.uml.backend;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SkinningWorkTest {
    @Test void staticPoseReusesResultButMorphOrSkeletonChangeInvalidatesIt() {
        SkinningWork work = new SkinningWork();
        assertTrue(work.needsSource(0)); assertTrue(work.needsDispatch(0,7,3));
        work.sourceUploaded(0); work.dispatched(0,7,3);
        assertFalse(work.needsSource(0)); assertFalse(work.needsDispatch(0,7,3));
        assertTrue(work.needsSource(1)); assertTrue(work.needsDispatch(1,7,3));
        // Upload success alone cannot publish an incomplete/failed transform-feedback result.
        work.sourceUploaded(1); assertTrue(work.needsDispatch(1,7,3)); work.dispatched(1,7,3);
        assertTrue(work.needsDispatch(1,8,3)); assertFalse(work.needsSource(1));
        assertTrue(work.needsDispatch(1,7,6));
        work.invalidate(); assertTrue(work.needsSource(1)); assertTrue(work.needsDispatch(1,7,3));
    }
}
