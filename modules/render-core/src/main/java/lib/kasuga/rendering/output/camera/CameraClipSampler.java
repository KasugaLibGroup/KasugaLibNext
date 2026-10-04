package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSource;

import java.util.EnumMap;

/** Camera output adapter for the same playback engine used by model AnimationSampler implementations. */
public final class CameraClipSampler implements AnimationSource<CameraAnimationClip, CameraPose> {
    public static final CameraClipSampler INSTANCE = new CameraClipSampler();
    private CameraClipSampler() {}
    public float duration(CameraAnimationClip data) { return data.durationSeconds(); }

    public CameraPose sample(CameraAnimationClip data, float time) {
        if (!Float.isFinite(time) || time < 0) throw new IllegalArgumentException("Sample time must be finite and non-negative");
        time = Math.min(time, data.durationSeconds());
        var channels = new EnumMap<CameraAnimationClip.Channel, Double>(CameraAnimationClip.Channel.class);
        for (var track : data.tracks()) channels.put(track.channel(), track.sample(time));
        return new CameraPose(channels);
    }
}
