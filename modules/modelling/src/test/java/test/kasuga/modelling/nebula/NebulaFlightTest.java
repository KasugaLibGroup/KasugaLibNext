package test.kasuga.modelling.nebula;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NebulaFlightTest {
    @Test void fixedCaptureStepsKeepPlaybackAndLightningAtRealTime() {
        var sim = new NebulaSimulation(42);
        for (int i = 0; i < 360; i++) sim.captureFrame();
        assertEquals(12,sim.time(1),.0001);
        assertEquals(3,sim.emitted());
        sim.paused(true);
        sim.captureFrame();
        assertEquals(12,sim.time(1),.0001);
    }
    @Test void oneSparseStrikeHasFineBranchesAndAVisibleReturnStroke() {
        var sim = new NebulaSimulation(42);
        for (int i = 0; i < 80; i++) sim.tick();
        assertEquals(1,sim.emitted());
        var bolt = sim.bolts().getFirst();
        assertEquals(32,bolt.segments().size());
        assertTrue(bolt.strength(bolt.born()+.17f) > .5f);
        assertTrue(NebulaSimulation.LIFETIME > .8f);
    }

    @Test void lightningLeaderProgressesTowardItsTargetInsteadOfDoublingBack() {
        var sim = new NebulaSimulation(42);
        sim.frequency(2);
        for (int tick = 0; tick < 200; tick++) {
            sim.tick();
            for (var bolt : sim.bolts()) {
                var origin = bolt.segments().getFirst().start();
                var direction = new Vector3f(bolt.segments().get(21).end()).sub(origin).normalize();
                float previous = 0;
                for (int i = 0; i < 22; i++) {
                    float progress = new Vector3f(bolt.segments().get(i).end()).sub(origin).dot(direction);
                    assertTrue(progress >= previous-.0001f, "Leader doubled back and formed a scribble");
                    previous = progress;
                }
            }
        }
    }
    @Test void trajectoryAndObliqueFollowStayFiniteAndInsideTheCloud() {
        for (int i = 0; i <= 6_000; i++) {
            var pose = NebulaFlight.sample(i * .05f);
            assertTrue(pose.position().isFinite());
            var p = pose.position();
            assertTrue(p.x*p.x/(21*21) + p.y*p.y/(12*12) + p.z*p.z/(15*15) < .85f*.85f);
            assertEquals(1, pose.forward().length(), .00001);
            assertEquals(1, pose.right().length(), .00001);
            assertEquals(1, pose.up().length(), .00001);
            assertEquals(0, pose.forward().dot(pose.right()), .00001);
            assertEquals(0, pose.forward().dot(pose.up()), .00001);
            var camera = NebulaFlight.camera(pose, 1280, 720, 0, 0);
            var offset = new Vector3f((float) camera.x(), (float) camera.y(), (float) camera.z()).sub(pose.position());
            assertTrue(offset.dot(pose.up()) > 1);
            assertTrue(offset.dot(pose.forward()) < -2);
            assertTrue(offset.dot(pose.right()) > 1);
            assertTrue(pose.toLocal().isFinite());
            assertTrue(pose.toLocal().transformDirection(new Vector3f(pose.forward())).distance(new Vector3f(0, 0, 1)) < .00001);
        }
    }

    @Test void wakeIsHistoricalSternWorldSpaceAndExpandsDownstream() {
        float[] wake = NebulaFlight.wake(10);
        assertEquals(24 * 4, wake.length);
        for (int i = 0; i < 24; i++) {
            var pose = NebulaFlight.sample(10 - i * NebulaFlight.WAKE_STEP);
            var expected = pose.stern();
            var actual = new Vector3f(wake[i*4], wake[i*4+1], wake[i*4+2]);
            assertTrue(actual.distance(expected) < .00001);
            assertTrue(actual.distance(pose.position()) > .20f, "Wake anchor was not offset to the stern");
            assertEquals(i * NebulaFlight.WAKE_STEP, wake[i*4+3], .00001);
        }
        assertArrayEquals(wake, NebulaFlight.wake(10));
        for (float value : NebulaFlight.wake(0)) assertTrue(Float.isFinite(value));

        float nearRadius = NebulaFlight.wakeRadius(0);
        assertEquals(nearRadius, NebulaFlight.wakeRadius(-1), .00001f);
        float previous = nearRadius;
        for (int step = 1; step <= 40; step++) {
            float current = NebulaFlight.wakeRadius(step * .5f);
            assertTrue(Float.isFinite(current) && current > previous,
                    "Wake radius did not expand monotonically at step " + step);
            previous = current;
        }
        assertTrue(NebulaFlight.wakeRadius(20) >= nearRadius * 5f, "Downstream wake did not spread wide enough");
        assertThrows(IllegalArgumentException.class, () -> NebulaFlight.wakeRadius(Float.NaN));
    }

    @Test void defaultLightningIsSparseAndPauseDoesNotAccumulateStrikes() {
        var sim = new NebulaSimulation(42);
        assertEquals(.25f, sim.frequency());
        for (int i = 0; i < 200; i++) sim.tick();
        assertEquals(2, sim.emitted());
        sim.paused(true);
        double time = sim.time(1);
        for (int i = 0; i < 200; i++) sim.tick();
        assertEquals(time, sim.time(1));
        assertEquals(2, sim.emitted());
        sim.paused(false);
        for (int i = 0; i < 120; i++) sim.tick();
        assertEquals(4, sim.emitted());
    }
}
