package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnimationPlayer dual-cadence playback tests. Clock semantics use the lerp convention:
 * {@code sample(partialTick)} reads {@code lerp(prevSeconds, seconds, partialTick)} — {@code partialTick=0}
 * is the previous tick's time, {@code partialTick=1} (boundary) the current tick's. Playback assertions
 * formerly lived on AnimationClipPoseDriverTest; loop semantics live here.
 */
class AnimationPlayerTest {

    private static final float EPS = 1e-3f;

    private static AnimationClip wheelClip() {
        // 0°→180° over 1s (360° would be identity quaternion and interpolate to 0 everywhere)
        return new AnimationClip(Id.parse("kasuga_lib:wheel"), 1f,
                List.of(new AnimationClip.BoneTrack("root",
                        List.of(
                                new AnimationClip.Keyframe(0f,
                                        new TransformDefinition(new Vector3f(), new Vector3f(0f, 0f, 0f), new Vector3f(1f, 1f, 1f)),
                                        null),
                                new AnimationClip.Keyframe(1f,
                                        new TransformDefinition(new Vector3f(), new Vector3f(0f, 180f, 0f), new Vector3f(1f, 1f, 1f)),
                                        null)))),
                List.of(), List.of(), List.of());
    }

    private static AnimationPlayer<AnimationClip> newPlayer() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        AnimationPlayer<AnimationClip> player = new AnimationPlayer<>(instance);
        instance.setPoseDriver(player);
        return player;
    }

    private static double rootAngle(AnimationPlayer<AnimationClip> player) {
        Transform bone = player.model().getSkeletonInstance().getTransforms().values().iterator().next();
        return Math.toDegrees(bone.getRotation().angle());
    }

    @Test
    void noopUntilPlay() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        assertFalse(player.isPlaying());
        player.tick(1f / 20f);
        player.sample(0f); // should not throw
    }

    @Test
    void playStartsPlaybackAndTickAdvances() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), true);
        assertTrue(player.isPlaying());
        player.tick(1f / 20f);
        player.sample(1f); // current tick time = 0.05
        // 0.05s into a 1s clip rotating 0→180° linearly → 9°
        assertEquals(9.0, rootAngle(player), 1.0);
    }

    @Test
    void partialTickInterpolatesBetweenTicks() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), true);
        player.tick(0.05f); // prev=0, seconds=0.05
        player.sample(0.5f); // elapsed = lerp(0, 0.05, 0.5) = 0.025 → 4.5°
        assertEquals(4.5, rootAngle(player), 1.0);
    }

    @Test
    void loopWrapsAroundDuration() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), true);
        player.tick(0.6f); // prev=0, seconds=0.6
        player.tick(0.6f); // prev=0.6, seconds=1.2 (monotonic; loop normalized in sample)
        player.sample(0.9f); // elapsed = lerp(0.6, 1.2, 0.9) = 1.14 → time = 0.14 → 25.2°
        assertEquals(25.2, rootAngle(player), 1.0);
        assertTrue(player.isPlaying());
    }

    @Test
    void nonLoopStopsAtDurationAndHoldsFinalFrame() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), false);
        player.tick(0.5f);
        player.sample(1f); // 90° at 0.5s
        assertEquals(90.0, rootAngle(player), 1.0);
        player.tick(0.6f); // clamps to duration and stops
        assertFalse(player.isPlaying());
        player.sample(1f); // completed clip → final frame (180°) is still written
        assertEquals(180.0, rootAngle(player), 1.0);
    }

    @Test
    void setSpeedScalesAdvancement() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), true);
        player.setSpeed(2f);
        player.tick(0.05f); // prev=0, seconds=0.1
        player.sample(1f);
        assertEquals(18.0, rootAngle(player), 1.0);
    }

    @Test
    void setSpeedRejectsInvalidValues() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        try {
            player.setSpeed(-1f);
            throw new AssertionError("negative speed must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            player.setSpeed(Float.NaN);
            throw new AssertionError("NaN speed must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void stopHaltsPlayback() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), true);
        player.stop();
        assertFalse(player.isPlaying());
        player.tick(1f / 20f);
        player.sample(0f); // should not throw
    }

    @Test
    void rebindSwapsSinkButKeepsProgress() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, wheelClip(), true);
        player.tick(1f / 20f); // prev=0, seconds=0.05
        player.rebind(ModelInstanceFixture.minimal());
        player.sample(1f);
        assertEquals(9.0, rootAngle(player), 1.0);
    }

    @Test
    void currentTimeAndDataReflectSnapshot() {
        AnimationPlayer<AnimationClip> player = newPlayer();
        AnimationClip clip = wheelClip();
        player.play(ClipSampler.INSTANCE, clip, true);
        player.tick(0.05f);
        assertEquals(0.05f, player.currentTime(), EPS);
        assertEquals(clip, player.currentData());
        player.stop();
        assertEquals(null, player.currentData());
    }

    @Test
    void playbackLoopsEquivalentToSamplerWrappedTime() {
        // The loop assertion formerly on AnimationSamplerTest: a looping player at 1.5s
        // samples the same pose as the pure sampler at 0.5s (loop is normalized sample-side).
        AnimationClip clip = wheelClip();
        AnimationPlayer<AnimationClip> player = newPlayer();
        player.play(ClipSampler.INSTANCE, clip, true);
        player.tick(0.5f);
        player.sample(1f); // time = 0.5 → 90°
        double atHalf = rootAngle(player);
        player.tick(1f); // prev=0.5, seconds=1.5
        player.sample(1f); // elapsed = 1.5 → wraps to time 0.5 → 90°
        assertEquals(atHalf, rootAngle(player), 0.1);
    }
}