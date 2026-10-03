package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A state node, generic over the owner type. Built fluently:
 * {@code layer.state("idle").durationTicks(2).onEnter(ctx -> ...)}.
 * Holds an optional {@code durationTicks} (auto-complete), enter/exit/update callbacks, pose-targets,
 * and an optional animation clip reference ({@link #clip}). A state may carry both a static pose and a
 * clip: the clip supplies bone motion, the static pose supplies morphs/frames.
 */
public final class State<Owner> {

    final String id;
    int durationTicks = -1;
    final List<Consumer<StateContext<Owner>>> onEnter = new ArrayList<>();
    final List<Consumer<StateContext<Owner>>> onExit = new ArrayList<>();
    final List<Consumer<StateContext<Owner>>> onUpdate = new ArrayList<>();
    final Pose.Builder pose = new Pose.Builder();
    AnimationSampler<?> clipSampler;
    Object clipData;
    boolean clipLoop;

    public State(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("state id required");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    //region configuration

    /** After this many ticks the state is "complete" and {@code whenComplete()} transitions fire. */
    public State<Owner> durationTicks(int ticks) {
        this.durationTicks = ticks;
        return this;
    }

    public State<Owner> durationSeconds(float seconds) {
        this.durationTicks = Math.max(0, Math.round(seconds * 20f));
        return this;
    }

    public State<Owner> onEnter(Consumer<StateContext<Owner>> callback) {
        onEnter.add(callback);
        return this;
    }

    public State<Owner> onExit(Consumer<StateContext<Owner>> callback) {
        onExit.add(callback);
        return this;
    }

    /** Runs every tick while this state is active; may imperatively {@code goTo/trigger/lockLayer}. */
    public State<Owner> onUpdate(Consumer<StateContext<Owner>> callback) {
        onUpdate.add(callback);
        return this;
    }

    //endregion

    //region pose

    public State<Owner> pose(Pose pose) {
        this.pose.merge(pose);
        return this;
    }

    public State<Owner> morph(Object id, float value, float factor) {
        this.pose.morph(id, value, factor);
        return this;
    }

    public State<Owner> morph(Object id, float value) {
        return morph(id, value, 1f);
    }

    public State<Owner> bone(String name, Transform transform, ApplyMode mode) {
        this.pose.bone(name, transform, mode);
        return this;
    }

    public State<Owner> bone(String name, Transform transform) {
        return bone(name, transform, ApplyMode.REPLACE);
    }

    public State<Owner> frame(Object materialRef, int frame) {
        this.pose.frame(materialRef, frame);
        return this;
    }

    /**
     * Reference an animation clip for this state. The FSM holds the playback clock and samples this
     * state's pose from {@code sampler} at render rate. A state
     * may carry both a static pose and a clip: the static pose supplies morphs/frames, the clip supplies
     * bone motion (clip bone entries win for same-named bones).
     *
     * @param sampler the animation sampler that interpolates {@code data}
     * @param data    the animation data (e.g. an {@code AnimationClip})
     * @param loop    whether the clip loops past its duration
     */
    public State<Owner> clip(AnimationSampler<?> sampler, Object data, boolean loop) {
        if (sampler == null) {
            throw new IllegalArgumentException("clip sampler required");
        }
        if (data == null) {
            throw new IllegalArgumentException("clip data required");
        }
        this.clipSampler = sampler;
        this.clipData = data;
        this.clipLoop = loop;
        return this;
    }

    //endregion

    /** Configured duration in ticks, or -1 if the state has no auto-complete duration. */
    public int durationTicks() {
        return durationTicks;
    }

    /** Whether this state references an animation clip. */
    public boolean hasClip() {
        return clipSampler != null && clipData != null;
    }

    public AnimationSampler<?> clipSampler() {
        return clipSampler;
    }

    public Object clipData() {
        return clipData;
    }

    public boolean clipLoop() {
        return clipLoop;
    }

    boolean hasDuration() {
        return durationTicks >= 0;
    }

    Pose buildPose() {
        return pose.build();
    }
}
