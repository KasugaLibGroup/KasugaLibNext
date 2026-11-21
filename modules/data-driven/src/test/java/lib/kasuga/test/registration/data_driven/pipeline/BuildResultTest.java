package lib.kasuga.test.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.pipeline.BuildResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildResultTest {
    @Test
    void onlyAppliedStatusCountsAsApplied() {
        assertTrue(new BuildResult(
                "demo",
                BuildResult.Status.APPLIED,
                null,
                List.of(),
                List.of()
        ).applied());
        assertFalse(new BuildResult(
                "demo",
                BuildResult.Status.FAILED,
                null,
                List.of(),
                List.of()
        ).applied());
    }
}
