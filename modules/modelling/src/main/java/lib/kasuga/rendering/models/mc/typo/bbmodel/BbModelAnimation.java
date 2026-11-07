package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.mc.util.ModelJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of a Blockbench {@code .bbmodel} file's {@code animations[]} array: a bone/group channel
 * animation (Blockbench's native animation container, distinct from the Bedrock {@code .animation.json}
 * format). Parsed by {@link BbModelDefinition#parse}, which already owns the model geometry half.
 *
 * <pre>{@code
 * {
 *   "name": "spin", "loop": "loop", "length": 2.0, "snapping": 24, "anim_time_update": "...",
 *   "animators": {
 *     "<uuid>": {
 *       "name": "wheel", "type": "bone",
 *       "keyframes": [
 *         { "channel": "rotation", "time": 0.0, "interpolation": "linear",
 *           "data_points": [ {"x": "0", "y": "0", "z": "0"}, {"x": "0", "y": "360", "z": "0"} ] }
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Each keyframe carries a {@code pre}/{@code post} {@link DataPoint} pair ({@code data_points[0]}
 * and {@code data_points[1]}; a single point means {@code post == pre}) plus optional bezier handles.
 * The interpolated segment runs from {@code pre.post} to {@code next.pre}, exactly as the 1.0 Forge
 * animation system sampled it. Channel values are stored as raw strings because Blockbench writes
 * formula expressions (e.g. {@code "query.anim_time * 45"}); constant floats are resolved eagerly and
 * non-constant strings degrade to {@code 0f} with a warning (raw strings are kept for a future formula
 * layer).
 *
 * @param name           animation name
 * @param loop           playback mode ({@code once} / {@code hold} / {@code loop})
 * @param length         total duration in seconds
 * @param snapping       Blockbench timeline frame rate the author keyed at
 * @param animTimeUpdate optional time-update expression (kept verbatim, not evaluated)
 * @param bones          per-bone channel keyframe tracks, keyed by bone name
 */
public record BbModelAnimation(
        String name,
        Loop loop,
        float length,
        int snapping,
        String animTimeUpdate,
        List<BoneAnim> bones
) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Playback mode of a Blockbench animation. */
    public enum Loop {
        ONCE, HOLD, LOOP;

        public static Loop get(String name) {
            if ("hold".equals(name)) return HOLD;
            if ("loop".equals(name)) return LOOP;
            return ONCE;
        }
    }

    /** The animated bone channels. */
    public enum Channel {
        POSITION("position"), ROTATION("rotation"), SCALE("scale");

        private final String name;

        Channel(String name) {
            this.name = name;
        }

        /** Resolve by Blockbench channel name, or {@code null} for unknown / absent names. */
        public static @Nullable Channel get(String name) {
            for (Channel channel : values()) {
                if (channel.name.equals(name)) return channel;
            }
            return null;
        }
    }

    /** Keyframe interpolation mode; segment interpolation picks the lowest-{@link #priority()} of the two endpoints. */
    public enum Interpolation {
        LINEAR(50), BEZIER(25), CATMULLROM(0), STEP(75);

        private final int priority;

        Interpolation(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }

        public static Interpolation get(String name) {
            if ("bezier".equals(name)) return BEZIER;
            if ("catmullrom".equals(name)) return CATMULLROM;
            if ("step".equals(name)) return STEP;
            return LINEAR;
        }
    }

    /** One bone's animated channels, each a time-sorted keyframe track. */
    public record BoneAnim(String bone, Map<Channel, List<Keyframe>> channels) {
    }

    /**
     * One keyframe on a channel: {@code time} seconds, the {@code pre}/{@code post} data points
     * (the segment samples {@code pre.post → next.pre}), and optional bezier handles.
     *
     * @param bezierLeft  left handle (relative to {@code pre}); anchored to {@code pre.pre}
     * @param bezierRight right handle (relative to {@code next}); anchored to {@code next.post}
     */
    public record Keyframe(
            float time,
            Interpolation interpolation,
            DataPoint pre,
            DataPoint post,
            @Nullable BezierHandle bezierLeft,
            @Nullable BezierHandle bezierRight
    ) {
    }

    /** A channel value: the resolved constant plus the raw Blockbench expression strings. */
    public record DataPoint(Vector3f value, String x, String y, String z) {
        private static final DataPoint ZERO = new DataPoint(new Vector3f(), "0", "0", "0");

        static DataPoint parse(JsonElement element) {
            if (element.isJsonArray()) {
                float[] values = ModelJson.GSON.fromJson(element, float[].class);
                String x = String.valueOf(values[0]), y = String.valueOf(values[1]), z = String.valueOf(values[2]);
                return new DataPoint(new Vector3f(values), x, y, z);
            }
            if (element.isJsonObject()) {
                PointInput input = ModelJson.GSON.fromJson(element, PointInput.class);
                String x = input.x == null ? "0" : input.x, y = input.y == null ? "0" : input.y, z = input.z == null ? "0" : input.z;
                return new DataPoint(new Vector3f(parseValue(x), parseValue(y), parseValue(z)), x, y, z);
            }
            return ZERO;
        }

        private static float parseValue(String raw) {
            try {
                return Float.parseFloat(raw.trim());
            } catch (NumberFormatException exception) {
                LOGGER.warn("BbModelAnimation: non-constant keyframe value '{}' is not supported yet (formula evaluation deferred); using 0", raw);
                return 0f;
            }
        }
    }

    /** A bezier control handle: per-axis time and value offsets, relative to the anchored keyframe. */
    public record BezierHandle(Vector3f time, Vector3f value) {
    }

    static BbModelAnimation parse(JsonObject json) {
        Input input = ModelJson.GSON.fromJson(json, Input.class);
        List<BoneAnim> bones = new ArrayList<>();
        for (AnimatorInput animator : input.animators.values()) {
            if (animator.name == null || animator.name.isEmpty()) continue;
            Map<Channel, List<Keyframe>> byChannel = new HashMap<>();
            for (KeyframeInput keyframe : animator.keyframes) {
                Channel channel = Channel.get(keyframe.channel);
                if (channel == null) continue;
                byChannel.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(keyframe.build());
            }
            if (byChannel.isEmpty()) continue;
            Map<Channel, List<Keyframe>> channels = new HashMap<>();
            byChannel.forEach((channel, keyframes) -> {
                keyframes.sort(Comparator.comparingDouble(Keyframe::time));
                channels.put(channel, List.copyOf(keyframes));
            });
            bones.add(new BoneAnim(animator.name, Map.copyOf(channels)));
        }
        return new BbModelAnimation(input.name == null ? "" : input.name, Loop.get(input.loop), input.length,
                input.snapping, input.animTimeUpdate == null ? "" : input.animTimeUpdate, List.copyOf(bones));
    }

    private static final class Input {
        String name = "", loop = "once", animTimeUpdate = "";
        Float length = 0f;
        Integer snapping = 24;
        Map<String, AnimatorInput> animators = Map.of();
    }
    private static final class AnimatorInput {
        String name = "";
        List<KeyframeInput> keyframes = List.of();
    }
    private static final class PointInput { String x = "0", y = "0", z = "0"; }
    private static final class KeyframeInput {
        String channel = "", interpolation = "";
        Float time = 0f;
        List<JsonElement> dataPoints = List.of();
        Vector3f bezierLeftTime, bezierLeftValue, bezierRightTime, bezierRightValue;
        Keyframe build() {
            List<DataPoint> points = new ArrayList<>(2);
            dataPoints.forEach(point -> points.add(DataPoint.parse(point)));
            if (points.isEmpty()) points.add(DataPoint.ZERO);
            if (points.size() == 1) points.add(points.get(0));
            BezierHandle left = bezierLeftTime == null || bezierLeftValue == null ? null : new BezierHandle(bezierLeftTime, bezierLeftValue);
            BezierHandle right = bezierRightTime == null || bezierRightValue == null ? null : new BezierHandle(bezierRightTime, bezierRightValue);
            return new Keyframe(time, Interpolation.get(interpolation), points.get(0), points.get(1), left, right);
        }
    }
}
