package test.kasuga.modelling.demo.nebula;

import lib.kasuga.rendering.output.WorldCameraView;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NebulaCameraTest {
    private static final NebulaFlight.Pose STILL = NebulaFlight.sample(0);

    private static WorldCameraView shot(float flightTime, float warpTime) {
        return NebulaFlight.camera(STILL, NebulaFlight.sampleWarp(warpTime), flightTime, 1280, 720, 0, 0);
    }

    private static Vector3f eye(WorldCameraView camera) {
        return new Vector3f((float) camera.x(), (float) camera.y(), (float) camera.z());
    }

    private static float projectedHullWidth(WorldCameraView camera) {
        var project = new Matrix4f().perspective((float) Math.toRadians(camera.verticalFov()),
                camera.aspectRatio(), .01f, 100).mul(NebulaFlight.inverseView(camera, new Matrix4f()).invert());
        float minX = 1, maxX = -1;
        for (int corner = 0; corner < 8; corner++) {
            var p = new Vector3f(STILL.position())
                    .fma(((corner & 1) == 0 ? -.42f : .42f) * NebulaFlight.SHIP_SCALE, STILL.right())
                    .fma(((corner & 2) == 0 ? -.23f : .23f) * NebulaFlight.SHIP_SCALE, STILL.up())
                    .fma(((corner & 4) == 0 ? -.66f : .66f) * NebulaFlight.SHIP_SCALE, STILL.forward());
            project.transformProject(p);
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
        }
        return (maxX - minX) * .5f;
    }

    @Test void closeShotFramesTheHullAtAnImmersiveScaleRatherThanAsADistantObject() {
        float width = projectedHullWidth(shot(0, 0));
        assertTrue(width >= .15f,
                "Hull still reads as a small distant object instead of a close tracking shot");
        assertTrue(width < .6f, "Close shot lost the surrounding nebula");
    }

    @Test void endingPullsBackToANebulaWideShotAndDoesNotReturnToTheOpeningCloseup() {
        var opening = shot(0, 2);
        var ending = shot(15, 2);
        float near = eye(opening).distance(STILL.position());
        float far = eye(ending).distance(STILL.position());
        assertTrue(far >= near * 3, "Ending must pull back substantially from the opening close shot");
        assertTrue(projectedHullWidth(ending) <= projectedHullWidth(opening) * .4f,
                "Ending still frames the ship too large to reveal the nebula");
        float previous = eye(shot(10.5f, 2)).distance(STILL.position());
        for (int frame = 1; frame <= 270; frame++) {
            float time = 10.5f + frame / 60f;
            float distance = eye(shot(time, 2)).distance(STILL.position());
            assertTrue(distance >= previous - .00001f, "Pullback reversed at " + time);
            previous = distance;
        }
        for (float time : new float[]{15.01f, 16, 20, 30})
            assertTrue(eye(shot(time, 2)).distance(STILL.position()) >= far * .9f,
                    "The ending cut back to the opening close shot at " + time);
        for (float boundary : new float[]{10.5f, 15}) {
            var before = shot(boundary - .0001f, 2);
            var after = shot(boundary + .0001f, 2);
            assertTrue(eye(before).distance(eye(after)) < .002f, "Pullback cut at " + boundary);
            assertTrue(Math.abs(before.verticalFov() - after.verticalFov()) < .03f);
        }
    }

    @Test void intermediateCameraKeysCarryMomentumInsteadOfStoppingAtEveryShot() {
        float h = .005f;
        for (float t : new float[]{7.35f, 8.15f, 9.2f, 9.55f, 11.65f}) {
            float before = NebulaFlight.sampleWarp(t - h).cameraMotion().azimuth();
            float after = NebulaFlight.sampleWarp(t + h).cameraMotion().azimuth();
            assertTrue(Math.abs((after - before) / (2 * h)) > 1,
                    "Camera lost its orbit momentum at intermediate key " + t);
        }
    }

    @Test void orbitAndBankCarryContinuousAccelerationThroughTheirDifferentResponseTimes() {
        float h = .02f;
        for (float key : new float[]{5.2f, 7.35f, 8.15f, 9.55f, 11.65f, 13.5f}) {
            for (boolean bank : new boolean[]{false, true}) {
                float t = key + (bank ? .19f : 0);
                float[] p = new float[5];
                for (int i = 0; i < p.length; i++) {
                    var motion = NebulaFlight.sampleWarp(t + (i - 2) * h).cameraMotion();
                    p[i] = bank ? motion.roll() : motion.azimuth();
                }
                float left = (p[2] - 2 * p[1] + p[0]) / (h * h);
                float right = (p[4] - 2 * p[3] + p[2]) / (h * h);
                assertTrue(Math.abs(left - right) < 12, "Acceleration cut at " + t + ", bank=" + bank);
            }
        }
    }

    @Test void cruiseMovesEvenWhenTheShipAndWarpPhaseAreStationary() {
        var a = shot(0, 2);
        var b = shot(5, 2);
        assertTrue(eye(a).distance(eye(b)) > .5f, "Cruise needs visible orbit/crane parallax");
        assertTrue(Math.abs(a.roll() - b.roll()) > 1);
        assertEquals(b, shot(5, 2), "Paused/repeated samples must not drift");
    }

    @Test void opticalAxisAndInverseViewAgreeIncludingRollAndShake() {
        for (float time : new float[]{0, 5.2f, 8.1f, 8.55f, 10.2f, 12.45f, 15, 86_000}) {
            var camera = shot(time, time);
            var inverse = NebulaFlight.inverseView(camera, new Matrix4f());
            assertTrue(inverse.isFinite());
            assertTrue(inverse.transformPosition(new Vector3f()).distance(eye(camera)) < .00001f);
            var optical = inverse.transformDirection(new Vector3f(0, 0, -1)).normalize();
            assertTrue(optical.distance(NebulaFlight.cameraForward(camera, new Vector3f())) < .00001f);
            var target = new Vector3f(STILL.position()).fma(.3f, STILL.forward());
            var projected = new Matrix4f().perspective((float) Math.toRadians(camera.verticalFov()),
                    camera.aspectRatio(), .01f, 100).mul(new Matrix4f(inverse).invert()).transformProject(target);
            assertTrue(Math.abs(projected.x) < .4f && Math.abs(projected.y) < .4f,
                    "Ship escaped the central framing at " + time + ": " + projected);
            assertTrue(projected.z > -1 && projected.z < 1);
        }
        assertThrows(IllegalArgumentException.class, () -> shot(Float.NaN, 0));
    }

    @Test void shotsRemainContinuousAcrossEveryPhaseAndCycleSeam() {
        for (float boundary : new float[]{0, 5.2f, 7.8f, 8.15f, 8.45f, 8.75f, 9.2f,
                11.8f, 12.15f, 12.25f, 13.5f, 13.65f, 13.75f, 15, 30}) {
            var before = shot(4, boundary - .0001f);
            var after = shot(4, boundary + .0001f);
            assertTrue(eye(before).distance(eye(after)) < .002f, "Position cut at " + boundary);
            assertTrue(Math.abs(before.roll() - after.roll()) < .01f, "Roll cut at " + boundary);
            assertTrue(Math.abs(before.verticalFov() - after.verticalFov()) < .03f, "FOV cut at " + boundary);
        }
        for (int fps : new int[]{30, 60}) {
            var previous = shot(0, 0);
            for (int frame = 1; frame <= fps * 30; frame++) {
                float t = frame / (float) fps;
                var camera = shot(t, t);
                assertTrue(eye(camera).distance(eye(previous)) < 9f / fps,
                        "Camera moved too far in one frame at " + t + " / " + fps + " FPS");
                previous = camera;
            }
        }
    }

    @Test void manualWarpDoesNotCutTheCameraInEitherTriggerWindow() {
        for (int ticks : new int[]{0, 35, 101, 276, 290, 303, 576}) {
            var simulation = new NebulaSimulation(42);
            for (int i = 0; i < ticks; i++) simulation.tick();
            float t = simulation.time(1);
            var before = shot(t, simulation.warpTime(1));
            assertTrue(simulation.triggerWarp(), "Expected an allowed trigger at " + t);
            var after = shot(t, simulation.warpTime(1));
            assertTrue(eye(before).distance(eye(after)) < .00001f, "Space cut the position at " + t);
            assertEquals(before.roll(), after.roll(), .00001f);
            assertEquals(before.yaw(), after.yaw(), .00001f);
            assertEquals(before.pitch(), after.pitch(), .00001f);
            assertEquals(before.verticalFov(), after.verticalFov(), .00001f);
        }
    }

    @Test void orbitExtremesAndLongFlightStayFiniteAndClearOfTheStretchedHull() {
        for (float t : new float[]{0, 8.1f, 8.55f, 10.2f, 12.45f, 30, 110, 300, 86_000}) {
            var pose = NebulaFlight.sample(t);
            var warp = NebulaFlight.sampleWarp(t);
            for (float azimuth : new float[]{-360, -180, 0, 180, 360}) {
                for (float elevation : new float[]{-27, 0, 48}) {
                    var camera = NebulaFlight.camera(pose, warp, t, 1280, 720, azimuth, elevation);
                    assertTrue(NebulaFlight.inverseView(camera, new Matrix4f()).isFinite());
                    // Conservative stretched ship envelope, including the saucer and twin nacelles.
                    assertTrue(eye(camera).distance(pose.position()) > warp.stretch() * NebulaFlight.SHIP_SCALE);
                }
            }
        }
    }
}
