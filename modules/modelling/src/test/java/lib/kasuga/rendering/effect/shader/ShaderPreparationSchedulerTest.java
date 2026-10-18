package lib.kasuga.rendering.effect.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShaderPreparationSchedulerTest {

    @Test
    void resolvesAutomaticAndExplicitWorkerCountsAgainstAvailableProcessors() {
        assertEquals(1, ShaderPreparationScheduler.resolveWorkerCount(0, 1));
        assertEquals(2, ShaderPreparationScheduler.resolveWorkerCount(0, 4));
        assertEquals(4, ShaderPreparationScheduler.resolveWorkerCount(0, 32));
        assertEquals(3, ShaderPreparationScheduler.resolveWorkerCount(3, 8));
        assertEquals(8, ShaderPreparationScheduler.resolveWorkerCount(32, 8));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPreparationScheduler.resolveWorkerCount(-1, 8));
    }
}
