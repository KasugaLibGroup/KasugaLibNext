package test.kasuga.modelling.demo.nebula;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NebulaLightningDataTest {
    @Test void oversizedBoltsAreClampedBeforeWritingEitherBuffer() {
        var segment = new NebulaSimulation.Segment(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6), .5f);
        var bolt = new NebulaSimulation.Bolt(0, new Vector3f(7, 8, 9), Collections.nCopies(200, segment));
        float[] lights = new float[8], segments = new float[512];
        var counts = assertDoesNotThrow(() -> NebulaLightningData.pack(Collections.nCopies(30, bolt), 0f, lights, segments));
        assertEquals(2, counts.lights()); assertEquals(64, counts.segments());
        assertEquals(7, lights[0]); assertEquals(.5f, segments[511]);
        assertTrue(counts.peak() > 0);
    }
    @Test void emptyAndExpiredBoltsProduceNoShaderEntries() {
        var bolt = new NebulaSimulation.Bolt(0, new Vector3f(), List.of());
        assertEquals(new NebulaLightningData.Counts(0, 0, 0), NebulaLightningData.pack(List.of(bolt), 5, new float[8], new float[512]));
        assertEquals(0, NebulaLightningData.pack(List.of(), 0, new float[0], new float[0]).segments());
        assertEquals(0, NebulaLightningData.pack(List.of(bolt), 0, new float[0], new float[0]).lights());
    }
    @Test void expiredFrameClearsPreviouslyPackedUniforms() {
        var segment = new NebulaSimulation.Segment(new Vector3f(1), new Vector3f(2), .5f);
        var bolt = new NebulaSimulation.Bolt(0, new Vector3f(3), List.of(segment));
        float[] lights = new float[16], segments = new float[512];
        NebulaLightningData.pack(List.of(bolt), 0, lights, segments);
        assertTrue(lights[3] > 0);
        NebulaLightningData.pack(List.of(), 5, lights, segments);
        assertArrayEquals(new float[16], lights);
        assertArrayEquals(new float[512], segments);
    }
}
