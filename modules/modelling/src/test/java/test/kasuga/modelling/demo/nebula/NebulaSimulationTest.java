package test.kasuga.modelling.demo.nebula;

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

    @Test void captureFrameRateControlsTheAnimationClockWithoutChangingTheTimeline() {
        var at30 = new NebulaSimulation(42);
        var at60 = new NebulaSimulation(42);
        at30.frequency(2);
        at60.frequency(2);
        for (int i = 0; i < 120; i++) at30.captureFrame(30);
        for (int i = 0; i < 240; i++) at60.captureFrame(60);
        assertEquals(4, at30.time(1), .0001);
        assertEquals(at30.time(1), at60.time(1), .0001);
        assertEquals(at30.emitted(), at60.emitted());
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

    @Test void triggerWarpAdvancesToChargeAndProtectsActivePhase() {
        var sim = new NebulaSimulation(42);
        // Initially at cruise (time = 0)
        assertEquals(0, sim.warpTime(1), 0.001f);
        assertTrue(sim.triggerWarp(), "Should trigger warp during cruise");
        assertEquals(5.15f, sim.warpTime(1), 0.001f);

        // While in charge or warp (time in [5.15, 13.75)), re-triggering must be rejected
        assertFalse(sim.triggerWarp(), "Should reject triggering while in warp charge");
        assertEquals(5.15f, sim.warpTime(1), 0.001f);

        // Advance into peak warp
        for (int i = 0; i < 80; i++) sim.tick(); // 4 seconds: 5.15 + 4.0 = 9.15s (peak warp)
        assertEquals(9.15f, sim.warpTime(1), 0.001f);
        assertFalse(sim.triggerWarp(), "Should reject triggering during active warp");
        assertEquals(9.15f, sim.warpTime(1), 0.001f);

        // Pausing does not advance warpTime
        sim.paused(true);
        float pausedTime = sim.warpTime(1);
        for (int i = 0; i < 40; i++) sim.tick();
        assertEquals(pausedTime, sim.warpTime(1), 0.0001f);
        sim.paused(false);

        // Advance to exit/post-exit (>= 13.75s)
        for (int i = 0; i < 100; i++) sim.tick(); // 5 seconds: 9.15 + 5.0 = 14.15s
        assertEquals(14.15f, sim.warpTime(1), 0.001f);
        assertTrue(sim.triggerWarp(), "Should trigger warp when near end of cycle");
        // Next cycle's 5.15s: 15.0 + 5.15 = 20.15s
        assertEquals(20.15f, sim.warpTime(1), 0.001f);

        // Reset clears offset
        sim.reset();
        assertEquals(0.0f, sim.warpTime(1), 0.0001f);
    }
}
