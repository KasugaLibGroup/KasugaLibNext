package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import org.jetbrains.annotations.Nullable;

/**
 * Content-side description of what a {@link ProxyHost} should attach: a state machine, a model, a
 * root pose, and how element state maps to machine parameters.
 *
 * <p>One template is normally a stateless singleton (or a small object created by the host). It is
 * <em>not</em> the proxy instance: {@link ProxyInstance} owns the per-element lifecycle and calls
 * back into the template. That split is what lets one template serve many elements — every method
 * receives the concrete element, so per-element decisions are made from the element, never from
 * the template's identity.
 *
 * <h2>Call order</h2>
 * <pre>
 * configure(element)        once, first tick of the host (after NBT load, level attach)
 *   &#8593; machine built lazily from {@link ProxyConfig#machine()}
 * pull(element, machine)    every tick, before the machine advances
 *   &#8593; machine.tick()
 * push(element, machine)    every tick, after transitions fired
 * onDetach(element)         once, on release (chunk unload / removal / explicit release)
 * </pre>
 *
 * <p>All methods run on the game thread. {@code pull}/{@code push} run on both sides; a template
 * that writes level state is responsible for checking the side itself.
 *
 * @param <T> the element type this proxy configures — the block entity, entity, item stack or
 *            external handle that implements {@link ProxyHost}, not the block/item itself
 */
/**
 * @deprecated compatibility description for the legacy {@link ProxyInstance} path. New content
 * declares a {@link lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyDefinition} and a typed
 * composition instead of exposing {@link StateMachine} to the element-facing API.
 */
@Deprecated(forRemoval = false)
public interface ElementProxy<T> {

    /**
     * Declares what to attach to this element. Called exactly once per proxy instance, on the
     * first game tick after the element is fully constructed and loaded.
     *
     * <p>Safe to read block state, NBT-backed fields and constructor arguments here. Not safe to
     * assume the player is nearby, the chunk is ticking, or the model has been published yet —
     * model binding retries on later ticks.
     *
     * @param element the concrete element being configured
     * @return the configuration to apply, or {@code null} to refuse this element (logged once;
     *         the proxy instance is disabled and never ticks again)
     */
    @Nullable
    ProxyConfig configure(T element);

    /**
     * Maps element state to machine parameters before the machine advances. Called once per game
     * tick, only when the machine was built successfully.
     *
     * <p>Typical use is {@code machine.setInternal(spec, value)} with the {@code ParameterSpec}
     * declared by the machine definition, so the parameter's type is checked at compile time.
     * Values written here are visible to this tick's transition guards.
     */
    default void pull(T element, StateMachine<?> machine) {
    }

    /**
     * Writes machine parameters back to the element after the machine advanced. Called once per
     * game tick, only when the machine was built successfully.
     *
     * <p>Values observed here are post-transition. Writing level state is authoritative only on
     * the server; gate client-side writes on the side when they could conflict.
     */
    default void push(T element, StateMachine<?> machine) {
    }

    /**
     * Per-instance render projection for formula tracks (the machine → render half, as opposed to
     * {@link #pull} / {@link #push} which connect the element to the machine).
     *
     * <p>Called once, when the proxy materializes, and handed to the animation driver. The provider
     * runs on the main thread once per game tick on the <b>client only</b> (the server has no
     * driver), writes derived ({@code externalWritable = false}) parameters back through
     * {@link StateMachine#setInternal}, and projects them into the formula namespace as
     * {@code query.*} so clip formula tracks can read them.
     *
     * <p><b>Return a new instance per element.</b> Providers keep per-instance state across ticks
     * (integrated speed, angle, progress); returning a shared instance makes every host drive the
     * same numbers. Return {@code null} when the content has no formula-driven parts.
     */
    default @Nullable VarProvider varProvider(T element) {
        return null;
    }

    /**
     * Releases anything this template acquired for the element. Called once, after the model has
     * been unbound and the machine torn down, and also for elements that never completed
     * {@link #configure(Object)}.
     */
    default void onDetach(T element) {
    }
}
