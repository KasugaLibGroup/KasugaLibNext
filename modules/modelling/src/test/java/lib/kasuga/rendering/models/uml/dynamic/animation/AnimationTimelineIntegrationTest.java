package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraAnimationClip;
import lib.kasuga.rendering.output.camera.OwnedCamera;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static lib.kasuga.rendering.output.camera.CameraAnimationClip.Channel.X;
import static org.junit.jupiter.api.Assertions.*;

class AnimationTimelineIntegrationTest {
    private static final WorldCameraView BASE = new WorldCameraView(30_000_000, 64, 0, 0, 0, 0, 70, 320, 180);

    private static AnimationClip clip(float duration) {
        var camera = CameraAnimationClip.builder(Id.parse("test:scene"), duration)
                .keyframe(X, 0, BASE.x(), null).keyframe(X, duration, BASE.x() + 10, null).build();
        return new AnimationClip(camera.id(), duration, List.of(new AnimationClip.BoneTrack("root", List.of(
                new AnimationClip.Keyframe(0, transform(0), null),
                new AnimationClip.Keyframe(duration, transform(10), null)))), List.of(), List.of(), List.of(),
                List.of(new AnimationClip.CameraTrack("shot", camera.tracks())));
    }

    private static TransformDefinition transform(float x) {
        return new TransformDefinition(new Vector3f(x, 0, 0), new Vector3f(), new Vector3f(1));
    }

    private static OwnedCamera camera() {
        return new OwnedCamera("test:output", () -> BASE, new OwnedCamera.Producer() {
            public void setEnabled(boolean enabled) {}
            public boolean isClosed() { return false; }
            public void close() {}
        }, new OwnedCamera.Output() {
            public boolean isClosed() { return false; }
            public void close() {}
        }, () -> {}, () -> {});
    }

    private record Scene(AnimationPlayer<AnimationClip> model, OwnedCamera camera, AnimationTimeline timeline) {
        static Scene create(float duration, boolean loop) {
            var timeline = new AnimationTimeline();
            var clip = clip(duration);
            var instance = ModelInstanceFixture.minimal();
            var model = new AnimationPlayer<AnimationClip>(instance);
            instance.setPoseDriver(model);
            var camera = AnimationTimelineIntegrationTest.camera();
            model.follow(ClipSampler.INSTANCE, clip, timeline);
            camera.animation().follow(clip, "shot", timeline);
            timeline.play(duration, loop);
            return new Scene(model, camera, timeline);
        }

        void assertPose(float partialTick, double position) {
            model.model().sample(partialTick);
            assertEquals(position, model.model().getSkeletonInstance().getTransforms().values().iterator().next().getPosition().x, 1e-5);
            assertEquals(BASE.x() + position, camera.samplePose(partialTick).x(), 1e-5);
        }
    }

    @Test void oneNativeClipDrivesBothTargetsWithoutDoubleTicking() throws Exception {
        var scene = Scene.create(1, true);
        scene.model().model().animate(.5f);
        scene.camera().tick(.5f);
        assertEquals(0, scene.timeline().currentTime());
        scene.timeline().tick(.5f);
        scene.assertPose(.5f, 2.5);
        scene.assertPose(1, 5);
        assertSame(scene.timeline(), scene.model().timeline());
        assertSame(scene.timeline(), scene.camera().animation().timeline());
        scene.camera().close();
    }

    @Test void sharedControlsPauseSeekSpeedAndResumeBothTargets() throws Exception {
        var scene = Scene.create(1, false);
        scene.timeline().tick(.25f);
        scene.camera().animation().pause();
        scene.timeline().tick(.5f);
        assertFalse(scene.model().isPlaying()); scene.assertPose(0, 2.5);
        scene.model().seek(.4f); scene.assertPose(0, 4);
        scene.camera().animation().resume();
        scene.model().setSpeed(2); scene.timeline().tick(.1f); scene.assertPose(1, 6);
        scene.timeline().setSpeed(0); scene.timeline().tick(1); scene.assertPose(0, 6);
        scene.camera().close();
    }

    @Test void sharedLoopWrapInterpolatesTheUnwrappedClock() throws Exception {
        var scene = Scene.create(1, true);
        scene.timeline().tick(.6f); scene.timeline().tick(.6f);
        scene.assertPose(.9f, 1.4);
        scene.timeline().seek(3.25f); scene.assertPose(0, 2.5);
        scene.camera().close();
    }

    @Test void completedModelAndCameraHoldTheFinalFrameOnLaterTicks() throws Exception {
        var scene = Scene.create(1, false);
        scene.timeline().tick(.75f); scene.timeline().tick(.5f);
        scene.assertPose(1, 10);
        scene.timeline().tick(.05f);
        for (float partial : new float[]{0, .25f, .75f, 1}) scene.assertPose(partial, 10);
        assertFalse(scene.model().isPlaying());
        scene.model().seek(0); scene.model().resume(); scene.timeline().tick(.5f); scene.assertPose(1, 5);
        scene.camera().close();
    }

    @Test void shorterTracksHoldTheirEndpointUntilTheSharedTimelineWraps() throws Exception {
        var scene = Scene.create(1, true);
        scene.timeline().play(2, true);
        scene.timeline().seek(1.5f); scene.assertPose(1, 10);
        scene.timeline().seek(2.25f); scene.assertPose(1, 2.5);
        scene.camera().close();
    }

    @Test void directCameraEditsAndCloseDetachOnlyThatTarget() throws Exception {
        var scene = Scene.create(1, true);
        scene.timeline().seek(.25f);
        scene.camera().moveBy(1, 0, 0);
        assertNull(scene.camera().animation().currentClip());
        scene.timeline().seek(.5f); scene.model().sample(1);
        assertEquals(BASE.x() + 3.5, scene.camera().pose().x());
        assertEquals(5, scene.model().model().getSkeletonInstance().getTransforms().values().iterator().next().getPosition().x);
        scene.camera().close(); assertTrue(scene.timeline().isPlaying());
        scene.model().stop(); assertTrue(scene.timeline().isPlaying());
    }

    @Test void aCameraCanFollowAnExistingStandaloneModelClock() throws Exception {
        var clip = clip(1);
        var model = new AnimationPlayer<AnimationClip>(ModelInstanceFixture.minimal());
        model.play(ClipSampler.INSTANCE, clip, true);
        assertThrows(IllegalStateException.class, () -> model.timeline().tick(.5f));
        var camera = camera();
        camera.animation().follow(clip, "shot", model.timeline());
        model.tick(.5f); camera.tick(.5f);
        assertEquals(.5, model.currentTime()); assertEquals(.5, camera.animation().currentTime());
        assertEquals(BASE.x() + 5, camera.samplePose(1).x());
        model.stop(); assertEquals(BASE, camera.samplePose(1)); camera.close();
    }

    @Test void modelRebindKeepsTheSharedTimelineAndItsProgress() throws Exception {
        var scene = Scene.create(1, true);
        scene.timeline().seek(.5f);
        var fresh = ModelInstanceFixture.minimal();
        fresh.setPoseDriver(scene.model()); scene.model().rebind(fresh);
        scene.assertPose(1, 5); assertSame(scene.timeline(), scene.model().timeline());
        scene.camera().close();
    }

    @Test void followBeforePlayAndExternalStopDoNotApplyAnAnimation() throws Exception {
        var scene = Scene.create(1, true);
        scene.timeline().stop();
        assertEquals(BASE, scene.camera().samplePose(1));
        scene.model().sample(1); assertFalse(scene.model().isPlaying());
        scene.timeline().play(1, false); scene.assertPose(1, 0);
        scene.camera().close();
    }

    @Test void invalidBindingsDoNotDiscardCurrentPlayback() {
        var model = new AnimationPlayer<AnimationClip>(ModelInstanceFixture.minimal());
        var clip = clip(1); model.play(ClipSampler.INSTANCE, clip, true);
        assertThrows(IllegalArgumentException.class, () -> model.follow(ClipSampler.INSTANCE, clip, model.timeline()));
        assertThrows(IllegalArgumentException.class, () -> model.play(null, clip, true));
        assertSame(clip, model.currentData()); assertTrue(model.isPlaying());
        assertThrows(IllegalArgumentException.class, () -> CameraAnimationClip.from(clip, "missing"));
    }

    @Test void nativeClipCodecRoundTripsBothTargetsAndKeepsOldJsonCompatible() {
        var original = clip(1);
        var json = AnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        assertEquals(original, AnimationClip.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        var old = AnimationClip.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"id\":\"test:old\",\"bones\":[]}")).getOrThrow();
        assertTrue(old.cameras().isEmpty()); assertEquals(1, old.durationSeconds());
    }

    @Test void invalidNativeCameraTracksProduceCodecErrors() {
        for (String cameras : List.of(
                "[{\"camera\":\"\",\"tracks\":[]}]",
                "[{\"camera\":\"shot\",\"tracks\":[]},{\"camera\":\"shot\",\"tracks\":[]}]",
                "[{\"camera\":\"shot\",\"tracks\":[{\"channel\":\"x\",\"keyframes\":[{\"time\":2,\"value\":1}]}]}]")) {
            var json = JsonParser.parseString("{\"id\":\"test:scene\",\"duration_seconds\":1,\"cameras\":" + cameras + "}");
            assertTrue(AnimationClip.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        }
    }

    @Test void standaloneStaticModelPosesAndInvalidTickDeltasKeepTheirContract() {
        var playback = new AnimationPlayback<String, Pose>();
        playback.play(new AnimationSource<>() {
            public float duration(String data) { return -1; }
            public Pose sample(String data, float time) { assertEquals(0, time); return Pose.empty(); }
        }, "static", false);
        playback.tick(Float.NaN); playback.tick(-1); assertEquals(0, playback.currentTime());
        playback.tick(.05f); assertEquals(Pose.empty(), playback.sample(0)); assertFalse(playback.isPlaying());
    }

    @Test void aLegacyNullModelSampleStillResetsPreviouslyPosedChannels() {
        var instance = ModelInstanceFixture.minimal();
        var player = new AnimationPlayer<String>(instance);
        var sampler = new AnimationSampler<String>() {
            public float duration(String data) { return 1; }
            public Pose sample(String data, float time) {
                return data.equals("posed") ? Pose.bone("root", ClipSampler.toTransform(transform(5))) : null;
            }
        };
        player.play(sampler, "posed", false); player.sample(0);
        assertFalse(instance.getSkeletonInstance().getTransforms().isEmpty());
        player.play(sampler, "clear", false); player.sample(0);
        assertTrue(instance.getSkeletonInstance().getTransforms().isEmpty());
    }
}
