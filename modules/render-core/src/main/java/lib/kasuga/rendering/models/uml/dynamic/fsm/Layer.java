package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One parallel state graph + its blend properties ({@link BlendMode} / {@code weight} / {@link BoneMask}).
 * Multiple layers run in parallel (orthogonal).
 *
 * <p>Built inside a {@code layer(id, layer -> ...)} lambda. {@link #state(String)} returns a typed
 * {@link State} handle, used in {@code transition(id, from, to)} (compile-time safe, no string ids).
 */
public final class Layer<Owner> {

    final String id;
    final List<State<Owner>> states = new ArrayList<>();
    final List<Transition<Owner>> transitions = new ArrayList<>();
    private Map<State<Owner>, List<Transition<Owner>>> transitionsByFrom;
    State<Owner> initial;

    // runtime
    State<Owner> active;
    Transition<Owner> activeTransition;
    float transitionElapsed;
    int stateElapsedTicks;
    State<Owner> pendingGoTo;
    boolean activeChanged;

    // clip clock: the active state's clip playback position (prev/current for partialTick interpolation).
    // Reset to 0 on every state entry; advanced every tick while the active state references a clip.
    float clipPrevTime;
    float clipTime;

    // blend props
    BlendMode mode = BlendMode.BASE;
    float weight = 1f;
    BoneMask boneMask = BoneMask.all();

    public Layer(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("layer id required");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    //region construction

    public State<Owner> state(String id) {
        return state(id, s -> {});
    }

    public State<Owner> state(String id, Consumer<State<Owner>> config) {
        for (State<Owner> existing : states) {
            if (existing.id().equals(id)) {
                throw new IllegalStateException("duplicate state id '" + id + "' in layer '" + this.id + "'");
            }
        }
        State<Owner> state = new State<>(id);
        config.accept(state);
        states.add(state);
        return state;
    }

    public Layer<Owner> initial(State<Owner> state) {
        this.initial = state;
        return this;
    }

    public Transition<Owner> transition(String id, State<Owner> from, State<Owner> to) {
        if (!states.contains(from) || !states.contains(to)) {
            throw new IllegalArgumentException("transition '" + id + "' in layer '" + this.id
                    + "' references a state from another layer (from/to must belong to this layer)");
        }
        Transition<Owner> transition = new Transition<>(id, this, from, to);
        transitions.add(transition);
        return transition;
    }

    public Layer<Owner> base() {
        this.mode = BlendMode.BASE;
        return this;
    }

    public Layer<Owner> additive() {
        this.mode = BlendMode.ADDITIVE;
        return this;
    }

    public Layer<Owner> override() {
        this.mode = BlendMode.OVERRIDE;
        return this;
    }

    public Layer<Owner> weight(float weight) {
        this.weight = weight;
        return this;
    }

    public Layer<Owner> boneMask(BoneMask mask) {
        this.boneMask = mask;
        return this;
    }

    //endregion

    //region accessors

    public BlendMode mode() {
        return mode;
    }

    public float weight() {
        return weight;
    }

    public BoneMask boneMask() {
        return boneMask;
    }

    public State<Owner> active() {
        return active;
    }

    /** Ticks the active state has been running (0 immediately after entering). */
    public int stateElapsedTicks() {
        return stateElapsedTicks;
    }

    /** The currently firing transition, or null if none. */
    public @Nullable Transition<Owner> activeTransition() {
        return activeTransition;
    }

    /** Index of the active state in {@link #states} (build order), or -1 when no state is active. */
    public int activeStateIndex() {
        return states.indexOf(active);
    }

    /** Index of the active transition in {@link #transitions} (build order), or -1 when none is in flight. */
    public int activeTransitionIndex() {
        return transitions.indexOf(activeTransition);
    }

    /** State at {@code index} in build order; null when out of bounds. */
    public @Nullable State<Owner> stateAt(int index) {
        return index >= 0 && index < states.size() ? states.get(index) : null;
    }

    /** Transition at {@code index} in build order; null when out of bounds. */
    public @Nullable Transition<Owner> transitionAt(int index) {
        return index >= 0 && index < transitions.size() ? transitions.get(index) : null;
    }

    /** Seconds elapsed in the current cross-fade (0 if no transition). */
    public float transitionElapsed() {
        return transitionElapsed;
    }

    /** External imperative switch within this layer. */
    public Layer<Owner> goTo(State<Owner> target) {
        this.pendingGoTo = target;
        return this;
    }

    void requestGoTo(State<Owner> target) {
        this.pendingGoTo = target;
    }

    State<Owner> findState(String id) {
        if (id == null) {
            return null;
        }
        for (State<Owner> state : states) {
            if (state.id().equals(id)) {
                return state;
            }
        }
        return null;
    }

    /**
     * Client reconciliation: set the active state by id, silently (no onExit/onEnter callbacks).
     * Clears any in-flight transition — call {@link #conformTransition} afterwards to restore one.
     *
     * @return true when the state id matched and the layer was conformed
     */
    boolean conformTo(String stateId, int elapsedTicks) {
        State<Owner> target = findState(stateId);
        if (target == null) {
            return false;
        }
        active = target;
        activeTransition = null;
        stateElapsedTicks = Math.max(0, elapsedTicks);
        clipPrevTime = 0f;
        clipTime = 0f;
        return true;
    }

    /** Legacy reconcile: same as {@link #conformTo(String, int)} with zero elapsed ticks. */
    boolean conformTo(String stateId) {
        return conformTo(stateId, 0);
    }

    /**
     * Client reconciliation: restore an in-flight cross-fade by transition id. No-op when the id is
     * unknown or neither the transition's source nor its target matches the currently active state.
     * Must be called after {@link #conformTo(String, int)} — that method clears the active
     * transition first. Note the active state stays on the transition's {@code from} state while a
     * cross-fade is in flight, so both ends are accepted.
     */
    void conformTransition(String transitionId, float elapsedSeconds) {
        Transition<Owner> transition = findTransition(transitionId);
        if (transition == null || (active != transition.from && active != transition.to)) {
            return;
        }
        activeTransition = transition;
        transitionElapsed = Math.max(0f, elapsedSeconds);
    }

    private @Nullable Transition<Owner> findTransition(String id) {
        if (id == null) {
            return null;
        }
        for (Transition<Owner> transition : transitions) {
            if (transition.id().equals(id)) {
                return transition;
            }
        }
        return null;
    }

    //endregion

    //region runtime

    void start() {
        active = initial != null ? initial : (states.isEmpty() ? null : states.get(0));
        stateElapsedTicks = 0;

        Map<State<Owner>, List<Transition<Owner>>> byFrom = new IdentityHashMap<>();
        for (Transition<Owner> transition : transitions) {
            byFrom.computeIfAbsent(transition.from, k -> new ArrayList<>()).add(transition);
        }
        this.transitionsByFrom = Collections.unmodifiableMap(byFrom);
    }

    /**
     * Advance one server tick. <b>Clock discipline</b> (intentional split):
     * {@code dt} is <em>real seconds</em> since the last tick and feeds cross-fade progress
     * ({@code transitionElapsed += dt}); {@code stateElapsedTicks} is a <em>call count</em> compared against
     * {@link State#durationTicks(int)} (which {@link State#durationSeconds(float)} rounds as {@code s*20}).
     * So {@code whenComplete} fires after N tick calls regardless of {@code dt}, while cross-fade runs in real
     * time. Hosts that tick at 20 Hz keep both in sync; variable-step callers should be aware of the split.
     */
    boolean tick(StateMachine<Owner> machine, float dt, long tickCount) {
        activeChanged = false;
        if (machine.consumeLock(id)) {
            // Frozen: keep the active pose, skip transitions.
            return false;
        }
        StateContext<Owner> ctx = new StateContext<>(machine, this, active, tickCount);

        if (active != null) {
            runActions(active.onUpdate, ctx);
        }
        advanceClipClock(dt);

        if (pendingGoTo != null && states.contains(pendingGoTo)) {
            forceEnter(pendingGoTo, ctx);
            pendingGoTo = null;
            stateElapsedTicks++;
            return activeChanged;
        }

        if (activeTransition != null) {
            transitionElapsed += dt;
            if (transitionElapsed >= activeTransition.crossFadeSeconds) {
                completeTransition(ctx);
            } else {
                return activeChanged;
            }
        }

        boolean sourceComplete = active != null
                && active.hasDuration()
                && stateElapsedTicks >= active.durationTicks;

        List<Transition<Owner>> candidates = transitionsByFrom.get(active);
        if (candidates != null) {
            for (Transition<Owner> transition : candidates) {
                if (transition.fires(ctx, sourceComplete)) {
                    fire(transition, ctx);
                    break;
                }
            }
        }
        stateElapsedTicks++;
        return activeChanged;
    }

    /**
     * Puppet tick: advance cross-fade interpolation only. No transition evaluation, no state actions, no
     * version bump, no {@code stateElapsedTicks} increment, no lock consumption — the server is authoritative
     * and the client conforms to {@link StateMachine#conform(StateMachineSnapshot)} snapshots, which supply
     * the active transition and its elapsed seconds. This lets a client smooth-interpolate between snapshots
     * without locally evaluating guards on vars it does not sync.
     */
    void advancePuppet(float dt) {
        advanceClipClock(dt);
        if (activeTransition != null) {
            transitionElapsed += dt;
            if (transitionElapsed >= activeTransition.crossFadeSeconds) {
                // complete without running onEnter/onExit (the server ran them)
                active = activeTransition.to;
                activeTransition = null;
                stateElapsedTicks = 0;
                clipPrevTime = 0f;
                clipTime = 0f;
            }
        }
    }

    private void fire(Transition<Owner> transition, StateContext<Owner> ctx) {
        runActions(transition.onFire, ctx);
        if (transition.isInstant()) {
            if (active != null) {
                runActions(active.onExit, ctx);
            }
            active = transition.to;
            stateElapsedTicks = 0;
            clipPrevTime = 0f;
            clipTime = 0f;
            if (active != null) {
                runActions(active.onEnter, ctx);
            }
            activeChanged = true;
        } else {
            activeTransition = transition;
            transitionElapsed = 0f;
            activeChanged = true;
        }
    }

    private void forceEnter(State<Owner> target, StateContext<Owner> ctx) {
        if (active != null) {
            runActions(active.onExit, ctx);
        }
        active = target;
        activeTransition = null;
        stateElapsedTicks = 0;
        clipPrevTime = 0f;
        clipTime = 0f;
        if (active != null) {
            runActions(active.onEnter, ctx);
        }
        activeChanged = true;
    }

    private void completeTransition(StateContext<Owner> ctx) {
        Transition<Owner> transition = activeTransition;
        if (active != null) {
            runActions(active.onExit, ctx);
        }
        active = transition.to;
        activeTransition = null;
        stateElapsedTicks = 0;
        clipPrevTime = 0f;
        clipTime = 0f;
        if (active != null) {
            runActions(active.onEnter, ctx);
        }
        activeChanged = true;
    }

    private static <O> void runActions(List<Consumer<StateContext<O>>> actions, StateContext<O> ctx) {
        if (actions == null) {
            return;
        }
        for (Consumer<StateContext<O>> action : actions) {
            action.accept(ctx);
        }
    }

    /**
     * Advance the active state's clip clock by {@code dt} real seconds. The clock is <b>monotonic</b>
     * (loop wrap is applied at sample time, mirroring {@code AnimationPlayer}) so the render
     * thread's {@code prevTime → time} partialTick lerp never interpolates backward across a loop seam.
     * Non-loop clips clamp at their duration (state transitions / duration handle completion). No-ops
     * (and re-zeroes) when the active state has no clip.
     */
    private void advanceClipClock(float dt) {
        if (active == null || !active.hasClip()) {
            clipPrevTime = 0f;
            clipTime = 0f;
            return;
        }
        clipPrevTime = clipTime;
        float next = clipTime + dt;
        float duration = PoseTarget.ClipTarget.duration(active.clipSampler(), active.clipData());
        clipTime = active.clipLoop() ? next : Math.min(next, duration);
    }

    /**
     * The active state's clip clock as a {@link PoseTarget.ClipTarget} (thread handoff), or null when the
     * active state references no clip.
     */
    @Nullable PoseTarget.ClipTarget clipTarget() {
        if (active == null || !active.hasClip()) {
            return null;
        }
        return new PoseTarget.ClipTarget(active.clipSampler(), active.clipData(), active.clipLoop(), clipPrevTime, clipTime);
    }

    /**
     * The pose of {@code state} at clip time {@code clipTime} seconds (monotonic; loop-wrap / end-clamp
     * normalization applied here): static pose merged first, then the clip sample (clip bone entries win
     * for same-named bones). States without a clip resolve to their static pose only; a null state yields
     * an empty pose.
     */
    Pose poseForState(State<Owner> state, float clipTime) {
        if (state == null) {
            return Pose.empty();
        }
        if (state.hasClip()) {
            float duration = PoseTarget.ClipTarget.duration(state.clipSampler(), state.clipData());
            float time = duration > 0f && state.clipLoop() ? clipTime % duration : Math.min(clipTime, duration);
            return new Pose.Builder()
                    .merge(state.buildPose())
                    .merge(PoseTarget.ClipTarget.sample(state.clipSampler(), state.clipData(), time))
                    .build();
        }
        return state.buildPose();
    }

    /** The pose this layer contributes this tick (cross-faded if a transition is in progress). */
    Pose activePose() {
        if (active == null) {
            return Pose.empty();
        }
        if (activeTransition != null) {
            float alpha = activeTransition.crossFadeSeconds <= 0f
                    ? 1f
                    : Math.min(1f, transitionElapsed / activeTransition.crossFadeSeconds);
            return PoseBlend.blend(poseForState(active, clipTime), poseForState(activeTransition.to, 0f), alpha);
        }
        return poseForState(active, clipTime);
    }

    //endregion
}
