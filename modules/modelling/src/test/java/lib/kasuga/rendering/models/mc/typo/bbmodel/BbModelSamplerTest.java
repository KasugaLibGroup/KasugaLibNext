package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbModelSamplerTest {

    private static BbModelAnimation animation(String json) {
        return BbModelDefinition.parse(json).animations().getFirst();
    }

    private static Pose.Bone bone(Pose pose, String name) {
        return pose.bones().get(name);
    }

    private static Vector3f rotateY(Pose.Bone bone, Vector3f input) {
        return bone.transform().apply(input);
    }

    @Test
    void durationReturnsAnimationLength() {
        BbModelAnimation animation = animation("""
                { "animations": [ { "name": "spin", "loop": "loop", "length": 2.5, "animators": {} } ] }
                """);
        assertEquals(2.5f, BbModelSampler.INSTANCE.duration(animation));
    }

    @Test
    void linearRotationInterpolatesBetweenSinglePointKeyframes() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "spin", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "wheel", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"}, {"x": "0", "y": "0", "z": "0"} ] },
                        { "channel": "rotation", "time": 1.0, "interpolation": "linear",
                          "data_points": [ {"x": "0", "y": "360", "z": "0"}, {"x": "0", "y": "360", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        Pose pose = BbModelSampler.INSTANCE.sample(animation, 0.5f);
        Pose.Bone bone = bone(pose, "wheel");
        assertEquals(ApplyMode.REPLACE, bone.mode());
        Vector3f direction = rotateY(bone, new Vector3f(0, 0, 1));
        assertEquals((float) Math.sin(Math.toRadians(180.0)), direction.x, 1e-4f);
        assertEquals(0.0f, direction.y, 1e-4f);
        assertEquals((float) Math.cos(Math.toRadians(180.0)), direction.z, 1e-4f);
    }

    @Test
    void snapsToExactKeyframeValuesAtSegmentEdges() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "spin", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "wheel", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ] },
                        { "channel": "rotation", "time": 1.0, "interpolation": "linear",
                          "data_points": [ {"x": "0", "y": "360", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        assertEquals(new Vector3f(0, 0, 1), rotateY(bone(BbModelSampler.INSTANCE.sample(animation, 0.0f), "wheel"), new Vector3f(0, 0, 1)));
        // 360° around Y is the identity — the exact last keyframe value.
        Vector3f full = rotateY(bone(BbModelSampler.INSTANCE.sample(animation, 1.0f), "wheel"), new Vector3f(0, 0, 1));
        assertEquals(0.0f, full.x, 1e-4f);
        assertEquals(0.0f, full.y, 1e-4f);
        assertEquals(1.0f, full.z, 1e-4f);
    }

    @Test
    void stepHoldsPreviousValueUntilTheNextKeyframe() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "steps", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "joint", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "step",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ] },
                        { "channel": "rotation", "time": 1.0, "interpolation": "step",
                          "data_points": [ {"x": "0", "y": "90", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        // Mid-segment holds the previous frame's value (0°).
        assertEquals(new Vector3f(0, 0, 1), rotateY(bone(BbModelSampler.INSTANCE.sample(animation, 0.5f), "joint"), new Vector3f(0, 0, 1)));
        // At the next keyframe the value snaps to 90° (R_y(90°): z-axis → x-axis).
        Vector3f snapped = rotateY(bone(BbModelSampler.INSTANCE.sample(animation, 1.0f), "joint"), new Vector3f(0, 0, 1));
        assertEquals(1.0f, snapped.x, 1e-4f);
        assertEquals(0.0f, snapped.z, 1e-4f);
    }

    @Test
    void catmullRomOvershootsTheLinearMidpointWithNeighbourTangents() {
        // Keyframes 0° → 30° → 0°, first segment linear+catmullrom (catmullrom wins the priority).
        // The leading segment extrapolates a virtual neighbour, so the t=0.5 value is 18.75° (not 15°).
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "wiggle", "loop": "loop", "length": 2.0,
                    "animators": {
                      "a": { "name": "cover", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ] },
                        { "channel": "rotation", "time": 1.0, "interpolation": "catmullrom",
                          "data_points": [ {"x": "0", "y": "30", "z": "0"} ] },
                        { "channel": "rotation", "time": 2.0, "interpolation": "catmullrom",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        Pose.Bone bone = bone(BbModelSampler.INSTANCE.sample(animation, 0.5f), "cover");
        Vector3f direction = rotateY(bone, new Vector3f(0, 0, 1));
        float expected = (float) Math.toRadians(18.75);
        assertEquals((float) Math.sin(expected), direction.x, 1e-4f);
        assertEquals(0.0f, direction.y, 1e-4f);
        assertEquals((float) Math.cos(expected), direction.z, 1e-4f);
    }

    @Test
    void bezierWithoutHandlesDegradesToLinear() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "blend", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "arm", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "bezier",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ] },
                        { "channel": "rotation", "time": 1.0, "interpolation": "bezier",
                          "data_points": [ {"x": "0", "y": "360", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        Pose.Bone bone = bone(BbModelSampler.INSTANCE.sample(animation, 0.5f), "arm");
        Vector3f direction = rotateY(bone, new Vector3f(0, 0, 1));
        assertEquals((float) Math.sin(Math.toRadians(180.0)), direction.x, 1e-4f);
        assertEquals((float) Math.cos(Math.toRadians(180.0)), direction.z, 1e-4f);
    }

    @Test
    void bezierCurveFollowsDeCasteljauControlPolyline() {
        // Cubic bezier 0 → 0 with symmetric control peaks at 2: P0=(0,0) P1=(0.25,2) P2=(1.25,2) P3=(1,0).
        // At u=0.5 the value axis reads 1.5.
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "swing", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "arm", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "bezier",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ],
                          "bezier_right_time": [0.25, 0.25, 0.25], "bezier_right_value": [0, 2, 0] },
                        { "channel": "rotation", "time": 1.0, "interpolation": "bezier",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ],
                          "bezier_left_time": [0.25, 0.25, 0.25], "bezier_left_value": [0, 2, 0] }
                      ]}
                    }
                  } ]
                }
                """);
        Pose.Bone bone = bone(BbModelSampler.INSTANCE.sample(animation, 0.5f), "arm");
        Vector3f direction = rotateY(bone, new Vector3f(0, 0, 1));
        assertEquals((float) Math.sin(Math.toRadians(1.5)), direction.x, 1e-4f);
        assertEquals(0.0f, direction.y, 1e-4f);
        assertEquals((float) Math.cos(Math.toRadians(1.5)), direction.z, 1e-4f);
    }

    @Test
    void positionChannelScalesPixelsToBlocksAndKeepsIdentityRotation() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "slide", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "cart", "type": "bone", "keyframes": [
                        { "channel": "position", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "16", "y": "0", "z": "0"} ] },
                        { "channel": "position", "time": 1.0, "interpolation": "linear",
                          "data_points": [ {"x": "32", "y": "0", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        Pose.Bone bone = bone(BbModelSampler.INSTANCE.sample(animation, 0.5f), "cart");
        Vector3f position = bone.transform().getPosition();
        assertEquals(1.5f, position.x, 1e-4f);
        assertEquals(0.0f, position.y, 1e-4f);
        assertEquals(0.0f, position.z, 1e-4f);
        // No rotation / scale channels → identity (plus the translation above).
        Vector3f applied = bone.transform().apply(new Vector3f(0, 1, 1));
        assertEquals(1.5f, applied.x, 1e-4f);
        assertEquals(1.0f, applied.y, 1e-4f);
        assertEquals(1.0f, applied.z, 1e-4f);
    }

    @Test
    void skipsBonesWithoutAnyKeyframedChannel() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "mixed", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "animated", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "0", "y": "45", "z": "0"} ] }
                      ]},
                      "b": { "name": "static", "type": "group", "keyframes": [] }
                    }
                  } ]
                }
                """);
        Pose pose = BbModelSampler.INSTANCE.sample(animation, 0.0f);
        assertTrue(pose.bones().containsKey("animated"));
        assertTrue(!pose.bones().containsKey("static"));
    }

    @Test
    void nonConstantDataPointDegradesToZeroAndKeepsRawString() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "expr", "loop": "loop", "length": 1.0,
                    "animators": {
                      "a": { "name": "bone", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "query.anim_time * 45", "y": "0", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        BbModelAnimation.Keyframe keyframe = animation.bones().getFirst().channels()
                .get(BbModelAnimation.Channel.ROTATION).getFirst();
        assertEquals("query.anim_time * 45", keyframe.pre().x());
        assertEquals(0.0f, keyframe.pre().value().x(), 1e-6f);
        Pose pose = BbModelSampler.INSTANCE.sample(animation, 0.5f);
        // Degraded to an identity rotation quaternion (w == 1).
        assertEquals(1.0f, bone(pose, "bone").transform().getRotation().w, 1e-6f);
        assertEquals(0.0f, bone(pose, "bone").transform().getRotation().x, 1e-6f);
    }

    @Test
    void composesMultiAxisAnimationRotationsInZyxOrder() {
        // Same euler order as the model's own rotations: intrinsic Z → Y → X (matrix Rz·Ry·Rx).
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "tilt", "loop": "hold", "length": 1.0,
                    "animators": {
                      "a": { "name": "arm", "type": "bone", "keyframes": [
                        { "channel": "rotation", "time": 0.0, "interpolation": "linear",
                          "data_points": [ {"x": "30", "y": "60", "z": "0"}, {"x": "30", "y": "60", "z": "0"} ] }
                      ]}
                    }
                  } ]
                }
                """);
        Pose pose = BbModelSampler.INSTANCE.sample(animation, 0.0f);

        Quaternionf zyx = new Quaternionf()
                .mul(new Quaternionf().rotationZ(0f))
                .mul(new Quaternionf().rotationY((float) Math.toRadians(60f)))
                .mul(new Quaternionf().rotationX((float) Math.toRadians(30f)));
        Vector3f expected = new Vector3f(0f, 0f, 1f).rotate(zyx);
        Vector3f actual = bone(pose, "arm").transform().apply(new Vector3f(0f, 0f, 1f));

        assertEquals(expected.x, actual.x, 1e-4f);
        assertEquals(expected.y, actual.y, 1e-4f);
        assertEquals(expected.z, actual.z, 1e-4f);
    }

    @Test
    void parsesLoopSnappingChannelsAndBezierHandles() {
        BbModelAnimation animation = animation("""
                {
                  "animations": [ {
                    "name": "wind", "loop": "hold", "length": 3.0, "snapping": 30,
                    "animators": {
                      "a": { "name": "fan", "type": "bone", "keyframes": [
                        { "channel": "scale", "time": 1.0, "interpolation": "step",
                          "data_points": [ {"x": "1", "y": "1", "z": "1"} ] },
                        { "channel": "rotation", "time": 0.0, "interpolation": "bezier",
                          "data_points": [ {"x": "0", "y": "0", "z": "0"} ],
                          "bezier_right_time": [0.5, 0.5, 0.5], "bezier_right_value": [0, 5, 0] }
                      ]}
                    }
                  } ]
                }
                """);
        assertEquals(BbModelAnimation.Loop.HOLD, animation.loop());
        assertEquals(3.0f, animation.length(), 1e-6f);
        assertEquals(30, animation.snapping());
        BbModelAnimation.BoneAnim bone = animation.bones().getFirst();
        assertEquals("fan", bone.bone());
        List<BbModelAnimation.Keyframe> rotation = bone.channels().get(BbModelAnimation.Channel.ROTATION);
        List<BbModelAnimation.Keyframe> scale = bone.channels().get(BbModelAnimation.Channel.SCALE);
        assertEquals(1, rotation.size());
        assertEquals(1, scale.size());
        assertEquals(BbModelAnimation.Interpolation.BEZIER, rotation.getFirst().interpolation());
        assertEquals(new Vector3f(0.5f, 0.5f, 0.5f), rotation.getFirst().bezierRight().time());
        assertEquals(new Vector3f(0f, 5f, 0f), rotation.getFirst().bezierRight().value());
        assertNull(rotation.getFirst().bezierLeft());
        // Single data point → post mirrors pre.
        assertEquals(rotation.getFirst().pre().value(), rotation.getFirst().post().value());
    }
}