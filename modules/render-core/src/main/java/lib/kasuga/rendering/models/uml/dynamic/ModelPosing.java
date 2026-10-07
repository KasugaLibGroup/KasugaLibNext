package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationLibrary;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationPlayer;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;

import java.util.Objects;

/** Static posing and named animation playback for any Model, composed from the common pose pipeline. */
public final class ModelPosing implements RebindablePoseDriver {
    private ModelInstance instance;
    private final AnimationPlayer<AnimationLibrary.Clip<?>> player;

    public ModelPosing(ModelInstance instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
        player = new AnimationPlayer<>(instance);
    }

    public boolean hasClip(String name) { return instance.getModel().getAnimations().contains(name); }
    public ModelInstance model() { return instance; }
    public String currentClip() {
        var clip = player.currentData();
        return clip == null ? null : clip.name();
    }

    /** Missing names return false and leave the active pose driver unchanged. */
    public boolean play(String name, boolean loop) {
        var clip = instance.getModel().getAnimations().get(name);
        if (clip == null) return false;
        player.play(AnimationLibrary.SAMPLER, clip, loop);
        instance.setPoseDriver(this);
        return true;
    }

    public boolean follow(String name, AnimationTimeline timeline) {
        var clip = instance.getModel().getAnimations().get(name);
        if (clip == null) return false;
        player.follow(AnimationLibrary.SAMPLER, clip, timeline);
        instance.setPoseDriver(this);
        return true;
    }

    public void pose(Pose pose) {
        player.play(AnimationLibrary.SAMPLER, AnimationLibrary.pose(pose), false);
        instance.setPoseDriver(this);
    }

    /** Neutralize this driver's bone/morph/IK channels on the next render sample. */
    public void stop() { pose(Pose.empty()); }
    public void pause() { player.pause(); }
    public void resume() { player.resume(); }
    public void seek(float seconds) { player.seek(seconds); }
    public void setSpeed(float speed) { player.setSpeed(speed); }
    public boolean isPlaying() { return player.isPlaying(); }
    public float currentTime() { return player.currentTime(); }
    public AnimationTimeline timeline() { return player.timeline(); }
    @Override public void tick(float dt) { player.tick(dt); }
    @Override public void sample(float partialTick) { player.sample(partialTick); }

    /** Refresh named clips from the new model, retaining time/speed/pause or an external shared clock. */
    @Override public void rebind(ModelInstance fresh) {
        Objects.requireNonNull(fresh);
        var current = player.currentData();
        var timeline = player.timeline();
        var clock = timeline == null ? null : timeline.snapshot();
        AnimationLibrary.Clip<?> replacement = current == null ? null : current.name() == null ? current
                : fresh.getModel().getAnimations().get(current.name());
        instance = fresh;
        if (replacement == null || clock == null) {
            player.rebind(fresh);
            player.play(AnimationLibrary.SAMPLER, AnimationLibrary.pose(Pose.empty()), false);
        } else {
            player.rebind(fresh, AnimationLibrary.SAMPLER, replacement);
        }
        fresh.setPoseDriver(this);
    }
}
