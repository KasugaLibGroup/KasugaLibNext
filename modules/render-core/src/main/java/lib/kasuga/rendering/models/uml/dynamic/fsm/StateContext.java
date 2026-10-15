package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The typed context handed to {@link Transition} guards and {@link State} callbacks. Fully generic over
 * {@link Owner}, so {@code ctx.owner()} returns the actor with no cast (type-safe). Carries the imperative
 * surface ({@code goTo/trigger/lockLayer}) and the typed value store ({@code get/set/vars}).
 *
 * <p>Scoped to one {@link Layer}/{@link State} pair, so structural reads ({@code stateElapsedTicks()},
 * {@code layerMode()}, ...) are direct typed methods — no path strings. The value store replaces the old
 * {@code data()}/{@code signal()} channel: {@code ctx.get(SPEED)}, {@code ctx.set(MODE, "fast")},
 * {@code ctx.isTriggered(ATTACK)}.
 */
public record StateContext<Owner>(
        StateMachine<Owner> machine,
        Layer<Owner> layer,
        State<Owner> state,
        long tick
) {

    /**
     * The owning actor — fully typed. Read its fields directly in conditions:
     * {@code ctx -> ctx.owner().moving}.
     */
    public Owner owner() {
        return machine.owner();
    }

    public boolean isClientSide() {
        return machine.isClientSide();
    }

    public long tickCount() {
        return tick;
    }

    public int version() {
        return machine.version();
    }

    //region structural (scoped to this layer/state)

    public Layer<Owner> layer() {
        return layer;
    }

    public State<Owner> state() {
        return state;
    }

    @Nullable
    public Transition<Owner> activeTransition() {
        return layer == null ? null : layer.activeTransition();
    }

    /** Ticks the active state has been running (0 immediately after entering). */
    public int stateElapsedTicks() {
        return layer == null ? 0 : layer.stateElapsedTicks();
    }

    /** Configured duration of the active state, or {@code -1} if it has none. */
    public int stateDurationTicks() {
        return state == null ? -1 : state.durationTicks();
    }

    public BlendMode layerMode() {
        return layer == null ? BlendMode.BASE : layer.mode();
    }

    public float layerWeight() {
        return layer == null ? 0f : layer.weight();
    }

    public boolean isLayerLocked() {
        return layer != null && machine.isLayerLocked(layer.id());
    }

    //endregion

    //region value store (replaces data()/signal())

    /** The typed value store (read-only view); values default to each var's default when unset. */
    public StateMap vars() {
        return machine.vars();
    }

    /** Read a typed value — the var's {@link StateVar#defaultValue()} if unset. */
    public <T> T get(StateVar<T> var) {
        return machine.vars().get(var);
    }

    /** True iff a value is explicitly set for {@code var} (a default does not count). */
    public boolean has(StateVar<?> var) {
        return machine.vars().has(var);
    }

    /** Validate and store {@code value} for {@code var}. */
    public <T> T set(StateVar<T> var, T value) {
        return machine.mutableVars().set(var, value);
    }

    /** Remove the explicitly-set value for {@code var} (subsequent {@code get} returns the default). */
    public <T> T remove(StateVar<T> var) {
        return machine.mutableVars().remove(var);
    }

    //endregion

    //region triggers

    /** Fire a tick-scoped trigger — an ephemeral {@link StateVar}{@code <Boolean>}; cleared at end of tick. */
    public void trigger(StateVar<Boolean> trigger) {
        machine.trigger(trigger);
    }

    /** Whether {@code trigger} is set this tick. */
    public boolean isTriggered(StateVar<Boolean> trigger) {
        return machine.isTriggered(trigger);
    }

    /** Raise a buffered (latched) trigger; consumed when a {@code Transition.onBuffered(var)} transition fires. */
    public void triggerBuffered(StateVar<Boolean> trigger) {
        machine.triggerBuffered(trigger);
    }

    /** Whether a buffered trigger is currently latched. */
    public boolean isBufferedTriggered(StateVar<Boolean> trigger) {
        return machine.isBufferedTriggered(trigger);
    }

    //endregion

    //region imperative

    /**
     * Imperatively switch to {@code target} within the current layer (resolved this tick).
     */
    public void goTo(State<Owner> target) {
        layer.requestGoTo(target);
    }

    /**
     * Lock another layer for {@code ticks} ticks (it stops evaluating transitions, but still emits its pose).
     */
    public void lockLayer(String layerId, int ticks) {
        machine.lockLayer(layerId, ticks);
    }

    /** Force-unlock a layer (cancels any remaining {@link #lockLayer} duration). */
    public void unlockLayer(String layerId) {
        machine.unlockLayer(layerId);
    }

    /**
     * Convenience for code that prefers a block: runs {@code body} with this context.
     */
    public void run(Consumer<StateContext<Owner>> body) {
        body.accept(this);
    }

    //endregion
}
