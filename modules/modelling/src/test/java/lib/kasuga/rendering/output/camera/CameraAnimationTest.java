package lib.kasuga.rendering.output.camera;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.output.WorldCameraView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static lib.kasuga.rendering.output.camera.CameraAnimationClip.Channel.*;
import static org.junit.jupiter.api.Assertions.*;

class CameraAnimationTest {
    private static final Id ID = Id.parse("kasuga_lib:camera");
    private static final WorldCameraView BASE = new WorldCameraView(30_000_000, 64, 8, 10, 20, 30, 70, 320, 180);

    private static CameraAnimationClip clip() {
        return CameraAnimationClip.builder(ID, 1)
                .keyframe(X, 0, BASE.x(), null).keyframe(X, 1, BASE.x() + 10, null)
                .rotate(0, 0, 0, 0, null).rotate(1, 720, 40, 80, null)
                .zoom(0, 1, null).zoom(1, 2, null).build();
    }

    private static CameraAnimationPlayer player(boolean loop) {
        var player = new CameraAnimationPlayer(() -> BASE);
        player.play(clip(), loop);
        return player;
    }

    @Test void independentChannelsRetainProviderValuesAndPrecision() {
        var pose = clip().sample(BASE, .25f);
        assertEquals(30_000_002.5, pose.x(), 1e-9);
        assertEquals(BASE.y(), pose.y()); assertEquals(BASE.z(), pose.z());
        assertEquals(180, pose.yaw()); assertEquals(10, pose.pitch()); assertEquals(20, pose.roll());
        assertEquals(1.25, Math.tan(Math.toRadians(BASE.verticalFov()) / 2)
                / Math.tan(Math.toRadians(pose.verticalFov()) / 2), 1e-6);
        assertEquals(BASE.width(), pose.width()); assertEquals(BASE.height(), pose.height());
    }

    @Test void easingShapesTheOutgoingSegmentAndRotationAllowsMultipleTurns() {
        var clip = CameraAnimationClip.builder(ID, 1)
                .keyframe(YAW, 0, 0, Easing.easeInQuad()).keyframe(YAW, 1, 720, null).build();
        assertEquals(180, clip.sample(BASE, .5f).yaw());
        assertEquals(360, clip().sample(BASE, .5f).yaw());
    }

    @Test void tracksSortAndCopyKeyframesAndHoldBothEndpoints() {
        var keys = new ArrayList<>(List.of(new CameraAnimationClip.Keyframe(1, 8, null),
                new CameraAnimationClip.Keyframe(.5f, 4, null)));
        var track = new CameraAnimationClip.Track(X, keys);
        keys.clear();
        assertEquals(4, track.sample(0)); assertEquals(6, track.sample(.75f)); assertEquals(8, track.sample(2));
        assertThrows(UnsupportedOperationException.class, () -> track.keyframes().clear());
    }

    @Test void invalidTracksAndNonFiniteValuesAreRejectedBeforePlayback() {
        var key = new CameraAnimationClip.Keyframe(0, 1, null);
        assertThrows(IllegalArgumentException.class, () -> new CameraAnimationClip.Track(X, List.of(key, key)));
        assertThrows(IllegalArgumentException.class, () -> new CameraAnimationClip.Track(X, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CameraAnimationClip.Keyframe(Float.NaN, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new CameraAnimationClip.Keyframe(0, Double.POSITIVE_INFINITY, null));
        assertThrows(IllegalArgumentException.class, () -> CameraAnimationClip.builder(ID, 1)
                .keyframe(YAW, 0, Double.MAX_VALUE, null).build());
        assertThrows(IllegalArgumentException.class, () -> CameraAnimationClip.builder(ID, 1).zoom(0, 0, null).build());
        assertThrows(IllegalArgumentException.class, () -> CameraAnimationClip.builder(ID, 1).zoom(2, 1, null).build());
        var track = new CameraAnimationClip.Track(X, List.of(key));
        assertThrows(IllegalArgumentException.class, () -> new CameraAnimationClip(ID, 1, List.of(track, track)));
        assertThrows(IllegalArgumentException.class, () -> new CameraAnimationClip(ID, -1, List.of()));
    }

    @Test void zoomOvershootAndExtremeMagnificationStayWithinValidProjectionRange() {
        var clip = CameraAnimationClip.builder(ID, 1)
                .zoom(0, 1, t -> 2).zoom(1, .1, null).build();
        float fov = clip.sample(BASE, .5f).verticalFov();
        assertTrue(fov > 0 && fov < 180);
        assertTrue(BASE.zoom(Double.MIN_VALUE).verticalFov() < 180);
        assertTrue(BASE.zoom(Double.MAX_VALUE).verticalFov() > 0);
        assertThrows(IllegalArgumentException.class, () -> BASE.zoom(-1));
    }

    @Test void codecRoundTripAndSparseJsonTracks() {
        var original = clip();
        var json = CameraAnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        assertEquals(original, CameraAnimationClip.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        var decoded = CameraAnimationClip.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"kasuga_lib:camera","duration_seconds":2,"tracks":[
                  {"channel":"yaw","keyframes":[
                    {"time":0,"value":0,"easing":"ease_in_quad"},{"time":2,"value":360}]}]}
                """)).getOrThrow();
        assertEquals(90, decoded.sample(BASE, 1).yaw());
        assertEquals(BASE.x(), decoded.sample(BASE, 1).x());
    }

    @Test void invalidJsonReportsDecodeErrorsInsteadOfThrowing() {
        for (String tracks : List.of(
                "[{\"channel\":\"unknown\",\"keyframes\":[]}]",
                "[{\"channel\":\"x\",\"keyframes\":[]}]",
                "[{\"channel\":\"zoom\",\"keyframes\":[{\"time\":0,\"value\":0}]}]",
                "[{\"channel\":\"x\",\"keyframes\":[{\"time\":2,\"value\":0}]}]",
                "[{\"channel\":\"x\",\"keyframes\":[{\"time\":0,\"value\":0},{\"time\":0,\"value\":1}]}]")) {
            var json = JsonParser.parseString("{\"id\":\"kasuga_lib:camera\",\"duration_seconds\":1,\"tracks\":" + tracks + "}");
            assertTrue(CameraAnimationClip.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent(), tracks);
        }
    }

    @Test void tickInterpolationAndLoopWrapUseAMonotonicClock() {
        var player = player(true);
        player.tick(.6f); player.tick(.6f);
        assertEquals(BASE.x() + 1.4, player.sample(.9f).x(), 1e-5);
        assertEquals(1.2, player.currentTime(), 1e-6);
        assertTrue(player.isPlaying());
        assertEquals(player.sample(.9f), player.sample(.9f));
    }

    @Test void completedClipSettlesToTheLastFrameAtEveryPartialTick() {
        var player = player(false);
        player.tick(.5f); player.tick(.6f);
        assertFalse(player.isPlaying());
        assertEquals(BASE.x() + 10, player.sample(1).x());
        player.tick(.05f);
        assertEquals(BASE.x() + 10, player.sample(0).x());
        player.resume(); assertFalse(player.isPlaying());
        player.stop(); assertEquals(BASE, player.sample(1));
    }

    @Test void pauseSeekResumeAndSpeedDoNotRewindWithinAFrame() {
        var player = player(false);
        player.tick(.25f); player.pause(); player.tick(.5f);
        assertEquals(BASE.x() + 2.5, player.sample(0).x());
        player.seek(.4f); assertFalse(player.isPlaying());
        assertEquals(BASE.x() + 4, player.sample(0).x(), 1e-6);
        player.resume(); player.setSpeed(2); player.tick(.1f);
        assertEquals(BASE.x() + 6, player.sample(1).x(), 1e-6);
        player.setSpeed(0); player.tick(1);
        assertEquals(player.sample(0), player.sample(1));
        assertThrows(IllegalArgumentException.class, () -> player.seek(-1));
        assertThrows(IllegalArgumentException.class, () -> player.setSpeed(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> player.tick(-1));
        assertThrows(IllegalArgumentException.class, () -> player.sample(Float.NaN));
    }

    @Test void zeroDurationClipHasAStablePose() {
        var player = new CameraAnimationPlayer(() -> BASE);
        player.play(CameraAnimationClip.builder(ID, 0).zoom(0, 2, null).build(), false);
        player.tick(.05f);
        assertFalse(player.isPlaying()); assertEquals(BASE.zoom(2), player.sample(.5f));
    }

    @Test void missingChannelsFollowALiveProviderAndZoomDoesNotAccumulate() {
        WorldCameraView[] source = {BASE};
        var player = new CameraAnimationPlayer(() -> source[0]);
        player.play(CameraAnimationClip.builder(ID, 1).zoom(0, 2, null).build(), true);
        source[0] = BASE.moveBy(3, 2, 1);
        assertEquals(source[0].zoom(2), player.sample(1));
        assertEquals(player.sample(1), player.sample(1));
    }
}
