package test.kasuga.modelling.nebula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NebulaSimulationTest {
    @Test void zeroFrequencyStopsNewStrikesAndLetsExistingLightFade() {
        var sim = new NebulaSimulation(42);
        sim.frequency(2);
        for (int i = 0; i < 20; i++) sim.tick();
        long count = sim.emitted();
        assertTrue(count > 0);
        sim.frequency(0);
        for (int i = 0; i < 200; i++) sim.tick();
        assertEquals(count, sim.emitted());
        assertTrue(sim.bolts().isEmpty());
    }

    @Test void maximumFrequencyIsMeasuredInStrikesPerSecondAndBounded() {
        var sim = new NebulaSimulation(42);
        sim.frequency(2);
        for (int i = 0; i < 20_000; i++) {
            sim.tick();
            assertTrue(sim.bolts().size() <= NebulaSimulation.MAX_BOLTS);
            for (var bolt : sim.bolts()) {
                assertTrue(bolt.strength(sim.time(1)) >= 0);
                for (var segment : bolt.segments()) {
                    assertTrue(segment.start().isFinite());
                    assertTrue(segment.end().isFinite());
                    assertTrue(segment.start().length() < 22);
                    assertTrue(segment.end().length() < 22);
                    assertTrue(insideCloud(segment.start()), "Lightning start escaped the cloud ellipsoid");
                    assertTrue(insideCloud(segment.end()), "Lightning end escaped the cloud ellipsoid");
                }
            }
        }
        assertEquals(2_000, sim.emitted());
        assertEquals(1_000, sim.time(1), .001);
    }

    private static boolean insideCloud(org.joml.Vector3f point) {
        return point.x * point.x / (21 * 21) + point.y * point.y / (12 * 12)
                + point.z * point.z / (15 * 15) <= .85f * .85f + .00001f;
    }

    @Test void frequencyChangesDoNotRestartTheSharedAnimationClock() {
        var sim = new NebulaSimulation(42);
        sim.tick();
        assertEquals(.025, sim.time(.5f), .00001);
        sim.frequency(0);
        sim.frequency(3);
        assertEquals(.05, sim.time(1), .00001);
        sim.tick();
        assertEquals(.075, sim.time(.5f), .00001);
        assertThrows(IllegalArgumentException.class, () -> sim.frequency(Float.NaN));
        sim.frequency(-1); assertEquals(0, sim.frequency());
        sim.frequency(99); assertEquals(2, sim.frequency());
    }

    @Test void restartResetsGeometryClockAndRandomSequence() {
        var sim = new NebulaSimulation(42);
        sim.frequency(2);
        for (int i = 0; i < 10; i++) sim.tick();
        var first = sim.bolts().getFirst().segments().getFirst().start();
        sim.reset();
        assertEquals(0, sim.time(1));
        assertEquals(0, sim.emitted());
        assertTrue(sim.bolts().isEmpty());
        sim.frequency(2);
        for (int i = 0; i < 10; i++) sim.tick();
        assertEquals(first, sim.bolts().getFirst().segments().getFirst().start());
    }
}
