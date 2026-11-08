package test.kasuga.modelling.demo.nebula;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NebulaFlightTest {
    @Test void warpCameraSweepsAroundAStationaryShipAndBanksIntoTheJump() {
        var pose = NebulaFlight.sample(0);
        var charge = NebulaFlight.camera(pose, NebulaFlight.sampleWarp(8.1f), 1280, 720, 0, 0);
        var cruise = NebulaFlight.camera(pose, NebulaFlight.sampleWarp(10.2f), 1280, 720, 0, 0);
        var a = new Vector3f((float) charge.x(), (float) charge.y(), (float) charge.z()).sub(pose.position());
        var b = new Vector3f((float) cruise.x(), (float) cruise.y(), (float) cruise.z()).sub(pose.position());
        double chargeAngle = Math.atan2(a.dot(pose.right()), -a.dot(pose.forward()));
        double cruiseAngle = Math.atan2(b.dot(pose.right()), -b.dot(pose.forward()));
        assertTrue(Math.toDegrees(chargeAngle - cruiseAngle) > 25,
                "Warp camera must sweep around the ship, rather than only changing radius/FOV");
        assertTrue(Math.abs(charge.roll() - cruise.roll()) > 6, "Jump must change camera bank");
    }

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
            assertTrue(offset.dot(pose.up()) > 0 && offset.dot(pose.forward()) < 0 && offset.dot(pose.right()) > 0);
            assertTrue(offset.length() > new Vector3f(.42f, .23f, .66f).length() * NebulaFlight.SHIP_SCALE + .1f);
            var projected = new org.joml.Matrix4f().perspective((float) Math.toRadians(camera.verticalFov()),
                    camera.aspectRatio(), .01f, 100)
                    .mul(NebulaFlight.inverseView(camera, new org.joml.Matrix4f()).invert())
                    .transformProject(new Vector3f(pose.position()));
            assertTrue(projected.isFinite() && Math.abs(projected.x) < .4f && Math.abs(projected.y) < .4f
                    && projected.z > -1 && projected.z < 1);
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

    @Test void wakeBoundsEncloseAllReachableWakeAndBowSamplesWithoutFalseNegatives() {
        var wake = new float[NebulaFlight.WAKE_POINTS * 4];
        var bounds = new float[24];
        for (float seconds : new float[]{0f, 7.5f, 19.25f, 84.6f, 86_000f}) {
            NebulaFlight.fillWakeAndBounds(seconds, wake, bounds);
            for (float v : wake) assertTrue(Float.isFinite(v));
            for (float v : bounds) assertTrue(Float.isFinite(v));
            assertArrayEquals(NebulaFlight.wake(seconds), wake, .00001f);

            var craft = NebulaFlight.sample(seconds).position();
            for (int axis = 0; axis < 3; axis++) {
                var plus = new Vector3f(craft);
                plus.setComponent(axis, plus.get(axis) + 2.4f);
                assertInside(bounds, 0, plus);
                var minus = new Vector3f(craft);
                minus.setComponent(axis, minus.get(axis) - 2.4f);
                assertInside(bounds, 0, minus);
            }

            for (int seg = 0; seg < NebulaFlight.WAKE_POINTS - 1; seg++) {
                int boxOffset = 6 + Math.min(seg / 8, 2) * 6;
                var a = new Vector3f(wake[seg * 4], wake[seg * 4 + 1], wake[seg * 4 + 2]);
                var b = new Vector3f(wake[(seg + 1) * 4], wake[(seg + 1) * 4 + 1], wake[(seg + 1) * 4 + 2]);
                float ageA = wake[seg * 4 + 3], ageB = wake[(seg + 1) * 4 + 3];
                for (int step = 0; step <= 4; step++) {
                    float u = step * .25f;
                    var onSeg = new Vector3f(a).lerp(b, u);
                    float reach = NebulaFlight.wakeRadius(ageA + (ageB - ageA) * u) * NebulaFlight.WAKE_REACH_MULTIPLIER;
                    for (var dir : new Vector3f[]{
                            new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                            new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
                            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1),
                            new Vector3f(1, 1, 1).normalize(), new Vector3f(-1, 1, -1).normalize()
                    }) {
                        var probe = new Vector3f(onSeg).fma(reach, dir);
                        assertInside(bounds, 0, probe);
                        assertInside(bounds, boxOffset, probe);
                    }
                }
            }
        }
    }

    @Test void prebakedNoiseAndWorleyAtlasesWrapSeamlesslyWithUnitNormals() {
        byte[] noise = NebulaTextures.buildNoiseLutRgba();
        assertEquals(NebulaTextures.NOISE_SIZE * NebulaTextures.NOISE_SIZE * 4, noise.length);
        int mask = NebulaTextures.NOISE_SIZE - 1;
        for (int y = 0; y < NebulaTextures.NOISE_SIZE; y++) {
            for (int x = 0; x < NebulaTextures.NOISE_SIZE; x++) {
                int idx = (y * NebulaTextures.NOISE_SIZE + x) * 4;
                int nx = (x + 37) & mask, ny = (y + 17) & mask;
                int nextIdx = (ny * NebulaTextures.NOISE_SIZE + nx) * 4;
                assertEquals(noise[nextIdx] & 255, noise[idx + 1] & 255, "Z+1 slice pairing broke at " + x + "," + y);
                assertEquals(255, noise[idx + 3] & 255);
            }
        }

        byte[] worley = NebulaTextures.buildWorleyAtlasRgba();
        assertEquals(NebulaTextures.CELL_ATLAS_WIDTH * NebulaTextures.CELL_ATLAS_HEIGHT * 4, worley.length);
        int tile = NebulaTextures.CELL_TILE, vox = NebulaTextures.CELL_VOXELS, width = NebulaTextures.CELL_ATLAS_WIDTH;
        for (int slice = 0; slice < vox; slice++) {
            int ox = (slice % NebulaTextures.CELL_COLS) * tile;
            int oy = (slice / NebulaTextures.CELL_COLS) * tile;
            for (int i = 1; i <= vox; i++) {
                assertTexelEquals(worley, width, ox + vox, oy + i, ox, oy + i);
                assertTexelEquals(worley, width, ox + 1, oy + i, ox + vox + 1, oy + i);
                assertTexelEquals(worley, width, ox + i, oy + vox, ox + i, oy);
                assertTexelEquals(worley, width, ox + i, oy + 1, ox + i, oy + vox + 1);
            }
            for (int vy = 1; vy <= vox; vy++) {
                for (int vx = 1; vx <= vox; vx++) {
                    int k = ((oy + vy) * width + ox + vx) * 4;
                    float nx = ((worley[k] & 255) / 255f) * 2f - 1f;
                    float ny = ((worley[k + 1] & 255) / 255f) * 2f - 1f;
                    float nz = ((worley[k + 2] & 255) / 255f) * 2f - 1f;
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    assertTrue(len > .95f && len < 1.05f, "Invalid cell normal length: " + len);
                }
            }
        }
    }

    private static void assertInside(float[] bounds, int offset, Vector3f p) {
        assertTrue(p.x >= bounds[offset] && p.y >= bounds[offset + 1] && p.z >= bounds[offset + 2]
                        && p.x <= bounds[offset + 3] && p.y <= bounds[offset + 4] && p.z <= bounds[offset + 5],
                () -> "Point " + p + " escaped bounds at offset " + offset);
    }

    private static void assertTexelEquals(byte[] rgba, int width, int x0, int y0, int x1, int y1) {
        int a = (y0 * width + x0) * 4, b = (y1 * width + x1) * 4;
        for (int c = 0; c < 4; c++) {
            assertEquals(rgba[a + c] & 255, rgba[b + c] & 255, "Tile wrap border mismatch at channel " + c);
        }
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

    @Test void warpProfileAndMonotonicDistanceStayFiniteAndBounded() {
        float peakStretch = 0;
        float peakFov = 0;
        float prevDist = -1;
        for (int i = 0; i <= 3_000; i++) {
            float t = i * 0.01f;
            var warp = NebulaFlight.sampleWarp(t);
            assertTrue(Float.isFinite(warp.charge()));
            assertTrue(Float.isFinite(warp.warpActive()));
            assertTrue(Float.isFinite(warp.stretch()));
            assertTrue(Float.isFinite(warp.flash()));
            assertTrue(Float.isFinite(warp.shockProgress()));
            assertTrue(Float.isFinite(warp.shockIntensity()));
            assertTrue(Float.isFinite(warp.warpSpeed()));
            assertTrue(Float.isFinite(warp.warpDistance()));
            assertTrue(Float.isFinite(warp.fov()));
            assertTrue(Float.isFinite(warp.cameraRadiusScale()));
            assertTrue(Float.isFinite(warp.cameraPitchOffset()));
            assertTrue(Float.isFinite(warp.shakeX()));
            assertTrue(Float.isFinite(warp.shakeY()));

            assertTrue(warp.stretch() >= 1.0f, "Stretch below 1.0 at t=" + t);
            peakStretch = Math.max(peakStretch, warp.stretch());
            peakFov = Math.max(peakFov, warp.fov());
            assertTrue(warp.fov() >= 65.0f && warp.fov() <= 110.0f, "Fov out of bounds at t=" + t);

            if (prevDist >= 0) {
                assertTrue(warp.warpDistance() >= prevDist - 1e-6f,
                        "warpDistance decreased at t=" + t + ": " + warp.warpDistance() + " < " + prevDist);
            }
            prevDist = warp.warpDistance();

            var pose = NebulaFlight.sample(t);
            var camera = NebulaFlight.camera(pose, warp, t, 1280, 720, 0, 0);
            var offset = new Vector3f((float) camera.x(), (float) camera.y(), (float) camera.z()).sub(pose.position());
            assertTrue(offset.dot(pose.up()) > 0 && offset.dot(pose.forward()) < 0,
                    "Camera left its elevated chase hemisphere at t=" + t);
            var envelope = new Vector3f(.42f, .23f, .66f * warp.stretch()).mul(NebulaFlight.SHIP_SCALE);
            assertTrue(offset.length() > envelope.length() + .1f, "Camera entered the stretched hull at t=" + t);
            var projection = new org.joml.Matrix4f().perspective((float) Math.toRadians(camera.verticalFov()),
                    camera.aspectRatio(), .01f, 100)
                    .mul(NebulaFlight.inverseView(camera, new org.joml.Matrix4f()).invert());
            // Test the complete conservative hull box, rather than the old arbitrary five-unit orbit offsets.
            for (int corner = 0; corner < 8; corner++) {
                var p = new Vector3f(pose.position())
                        .fma((corner & 1) == 0 ? -envelope.x : envelope.x, pose.right())
                        .fma((corner & 2) == 0 ? -envelope.y : envelope.y, pose.up())
                        .fma((corner & 4) == 0 ? -envelope.z : envelope.z, pose.forward());
                projection.transformProject(p);
                assertTrue(p.isFinite() && Math.abs(p.x) < .75f && Math.abs(p.y) < .75f
                        && p.z > -1 && p.z < 1, "Stretched hull escaped the frame at t=" + t + ": " + p);
            }
        }
        assertTrue(peakStretch >= 2.2f, "Peak stretch was not reached: " + peakStretch);
        assertTrue(peakFov >= 90.0f, "Peak FOV was not reached: " + peakFov);

        var w0 = NebulaFlight.sampleWarp(0f);
        var w15 = NebulaFlight.sampleWarp(15f);
        assertEquals(w0.charge(), w15.charge(), 0.001f);
        assertEquals(w0.warpActive(), w15.warpActive(), 0.001f);
        assertEquals(w0.stretch(), w15.stretch(), 0.001f);
        assertEquals(w0.warpSpeed(), w15.warpSpeed(), 0.001f);
        assertEquals(w0.fov(), w15.fov(), 0.001f);
        assertEquals(w0.cameraRadiusScale(), w15.cameraRadiusScale(), 0.001f);
        assertEquals(w0.cameraPitchOffset(), w15.cameraPitchOffset(), 0.001f);

        var wFar = NebulaFlight.sampleWarp(86_000f);
        assertTrue(Float.isFinite(wFar.warpDistance()));
        assertTrue(Float.isFinite(wFar.fov()));
        assertTrue(Float.isFinite(wFar.stretch()));
    }

    @Test void nacelleParticleTrajectorySamplesAreFiniteAndBounded() {
        for (float t : new float[]{0f, 5.5f, 8.55f, 10.2f, 12.45f, 14.9f, 86_000f}) {
            var warp = NebulaFlight.sampleWarp(t);
            float maxReach = 1.6f + warp.warpActive() * 5.2f;
            float flowPhase = warp.warpDistance() * 0.35f + t * 1.6f;
            for (int i = 0; i < 64; i++) {
                float engineSign = (i < 32) ? -1.0f : 1.0f;
                int localIdx = i % 32;
                float seedJitter = (float) (Math.sin(i * 12.9898 + 78.233) * 43758.5453) % 1.0f;
                float speed = 0.85f + 0.30f * Math.abs((float) (Math.sin((i + 13) * 37.1) * 23421.6) % 1.0f);
                float u = Math.abs((localIdx + seedJitter) / 32.0f + flowPhase * speed) % 1.0f;
                float trailDist = u * maxReach;
                float sampleT = t - (trailDist / 2.6f);
                var histPose = NebulaFlight.sample(sampleT);
                assertTrue(histPose.position().isFinite());
                assertTrue(histPose.forward().isFinite());
            }
        }
    }
}
