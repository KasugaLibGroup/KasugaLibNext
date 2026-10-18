package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.google.gson.JsonArray;
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
                JsonArray array = element.getAsJsonArray();
                String x = String.valueOf(array.get(0).getAsFloat());
                String y = String.valueOf(array.get(1).getAsFloat());
                String z = String.valueOf(array.get(2).getAsFloat());
                return new DataPoint(new Vector3f(Float.parseFloat(x), Float.parseFloat(y), Float.parseFloat(z)), x, y, z);
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                String x = getString(object, "x", "0");
                String y = getString(object, "y", "0");
                String z = getString(object, "z", "0");
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
        String name = getString(json, "name", "");
        Loop loop = Loop.get(getString(json, "loop", "once"));
        float length = json.has("length") ? json.get("length").getAsFloat() : 0f;
        int snapping = json.has("snapping") ? json.get("snapping").getAsInt() : 24;
        String animTimeUpdate = getString(json, "anim_time_update", "");

        List<BoneAnim> bones = new ArrayList<>();
        if (json.has("animators")) {
            JsonObject animators = json.getAsJsonObject("animators");
            for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                JsonObject animator = entry.getValue().getAsJsonObject();
                String bone = getString(animator, "name", "");
                if (bone.isEmpty()) continue;
                Map<Channel, List<Keyframe>> byChannel = new HashMap<>();
                if (animator.has("keyframes")) {
                    for (JsonElement keyframeElement : animator.getAsJsonArray("keyframes")) {
                        JsonObject keyframeObject = keyframeElement.getAsJsonObject();
                        Channel channel = Channel.get(getString(keyframeObject, "channel", ""));
                        if (channel == null) continue;
                        byChannel.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(parseKeyframe(keyframeObject));
                    }
                }
                for (List<Keyframe> keyframes : byChannel.values()) {
                    keyframes.sort(Comparator.comparingDouble(Keyframe::time));
                }
                if (byChannel.isEmpty()) continue;
                Map<Channel, List<Keyframe>> channels = new HashMap<>();
                byChannel.forEach((channel, keyframes) -> channels.put(channel, List.copyOf(keyframes)));
                bones.add(new BoneAnim(bone, Map.copyOf(channels)));
            }
        }
        return new BbModelAnimation(name, loop, length, snapping, animTimeUpdate, List.copyOf(bones));
    }

    private static Keyframe parseKeyframe(JsonObject json) {
        float time = json.has("time") ? json.get("time").getAsFloat() : 0f;
        Interpolation interpolation = Interpolation.get(getString(json, "interpolation", ""));
        List<DataPoint> points = new ArrayList<>(2);
        if (json.has("data_points")) {
            for (JsonElement point : json.getAsJsonArray("data_points")) {
                points.add(DataPoint.parse(point));
            }
        }
        if (points.isEmpty()) points.add(DataPoint.ZERO);
        if (points.size() == 1) points.add(points.get(0));
        BezierHandle left = parseHandle(json, "bezier_left_time", "bezier_left_value");
        BezierHandle right = parseHandle(json, "bezier_right_time", "bezier_right_value");
        return new Keyframe(time, interpolation, points.get(0), points.get(1), left, right);
    }

    private static @Nullable BezierHandle parseHandle(JsonObject json, String timeKey, String valueKey) {
        if (!json.has(timeKey) || !json.has(valueKey)) return null;
        return new BezierHandle(vector3(json.getAsJsonArray(timeKey)), vector3(json.getAsJsonArray(valueKey)));
    }

    private static Vector3f vector3(JsonArray values) {
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
}