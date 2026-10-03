package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Stateless variant-graph <b>definition</b>: typed {@link Variant} nodes + guarded transitions with
 * optional cross-fade. Holds NO runtime state — one instance can be shared per definition key (e.g. a
 * block type). Per-instance runtime state lives in a {@link MuxState} that the owner holds.
 *
 * <p>The multiplexer is generic over the {@link Context} type (the input snapshot) and the concrete
 * {@link Variant} type (the selected output). The Minecraft implementation uses {@code McContext} and
 * {@code McVariant}; the core knows nothing about Minecraft.
 *
 * <pre>{@code
 * Multiplexer<MyContext, MyVariant> def = Multiplexer.define(MyVariant::new, mux -> {
 *     MyVariant off = mux.variant("off", v -> { });
 *     MyVariant on  = mux.variant("on",  v -> { });
 *     mux.transition(off, on, t -> t.when(ctx -> ctx.property("powered").equals("true")).crossFade(0.2f));
 *     mux.initial(off);
 * });
 * MuxState<MyVariant> state = def.newState();
 * def.advance(state, context, dt);
 * }</pre>
 */
public final class Multiplexer<C extends Context, V extends Variant<V>> {

    private final List<V> variants;
    private final Map<String, V> variantsById;
    private final List<Transition<C, V>> transitions;
    private final Map<V, List<Transition<C, V>>> transitionsByFrom;
    private final V initial;

    public Multiplexer(List<V> variants, List<Transition<C, V>> transitions, V initial) {
        this.variants = List.copyOf(variants);
        Map<String, V> byId = new LinkedHashMap<>();
        for (V variant : this.variants) {
            byId.put(variant.id(), variant);
        }
        this.variantsById = Map.copyOf(byId);
        this.transitions = List.copyOf(transitions);

        Map<V, List<Transition<C, V>>> byFrom = new IdentityHashMap<>();
        for (Transition<C, V> transition : this.transitions) {
            byFrom.computeIfAbsent(transition.from(), k -> new ArrayList<>()).add(transition);
        }
        this.transitionsByFrom = Collections.unmodifiableMap(byFrom);

        this.initial = initial;
    }

    public static <C extends Context, V extends Variant<V>> Multiplexer<C, V> define(
            VariantFactory<V> factory, Consumer<Builder<C, V>> config) {
        Builder<C, V> builder = new Builder<>(factory);
        config.accept(builder);
        return builder.build();
    }

    public V initial() {
        return initial;
    }

    public V variant(String id) {
        return variantsById.get(id);
    }

    public List<V> variants() {
        return variants;
    }

    public List<Transition<C, V>> transitions() {
        return transitions;
    }

    /** Create a fresh per-instance {@link MuxState} starting at the initial variant. */
    public MuxState<V> newState() {
        return new MuxState<>(initial);
    }

    /**
     * Given the current state and a context, return the variant the multiplexer would select now
     * (the target of the first matching transition from the current variant, or the current variant
     * if none match). This does not mutate state or start a cross-fade.
     */
    public V select(C context, MuxState<V> state) {
        if (state.inTransition()) {
            return state.to();
        }
        List<Transition<C, V>> candidates = transitionsByFrom.get(state.current());
        if (candidates == null) {
            return state.current();
        }
        for (Transition<C, V> transition : candidates) {
            if (transition.guard().test(context)) {
                return transition.to();
            }
        }
        return state.current();
    }

    /**
     * Advance the external {@code state}: progress any in-flight cross-fade by {@code dt}, then
     * evaluate transition guards against {@code context} and start/commit a switch.
     */
    public void advance(MuxState<V> state, C context, float dt) {
        if (state.inTransition()) {
            state.advance(dt);
            if (state.transitionDone()) {
                state.commitTransition();
            } else {
                return;
            }
        }
        List<Transition<C, V>> candidates = transitionsByFrom.get(state.current());
        if (candidates == null) {
            return;
        }
        for (Transition<C, V> transition : candidates) {
            if (!transition.guard().test(context)) {
                continue;
            }
            if (transition.crossFadeSeconds() <= 0f) {
                state.setCurrentInstant(transition.to());
            } else {
                state.startTransition(state.current(), transition.to(), transition.crossFadeSeconds());
            }
            if (transition.onSwitch() != null) {
                transition.onSwitch().accept(state);
            }
            break;
        }
    }

    /** One directed edge: {@code from} → {@code to}, taken when {@code guard} holds. */
    public record Transition<C extends Context, V extends Variant<V>>(
            V from,
            V to,
            Predicate<C> guard,
            float crossFadeSeconds,
            Consumer<MuxState<V>> onSwitch
    ) {

        public Transition {
            Objects.requireNonNull(from, "transition 'from' required");
            Objects.requireNonNull(to, "transition 'to' required");
            if (guard == null) {
                guard = c -> true;
            }
        }
    }

    //region builder

    public static final class Builder<C extends Context, V extends Variant<V>> {

        private final VariantFactory<V> factory;
        private final List<V> variants = new ArrayList<>();
        private final List<Transition<C, V>> transitions = new ArrayList<>();
        private V initial;

        Builder(VariantFactory<V> factory) {
            this.factory = Objects.requireNonNull(factory, "variant factory required");
        }

        /** Define a variant and return its typed handle (capture it to reference in transitions/initial). */
        public V variant(String id, Consumer<V> config) {
            V variant = factory.create(id);
            config.accept(variant);
            variants.add(variant);
            return variant;
        }

        public Builder<C, V> transition(V from, V to, Consumer<TransitionBuilder<C, V>> config) {
            TransitionBuilder<C, V> builder = new TransitionBuilder<>(from, to);
            config.accept(builder);
            transitions.add(builder.build());
            return this;
        }

        public Builder<C, V> initial(V variant) {
            this.initial = variant;
            return this;
        }

        public Multiplexer<C, V> build() {
            if (variants.isEmpty()) {
                throw new IllegalStateException("multiplexer needs at least one variant");
            }
            if (initial == null) {
                initial = variants.get(0);
            }
            return new Multiplexer<>(variants, transitions, initial);
        }
    }

    /** Fluent transition configurator used inside {@code transition(from, to, t -> ...)}. */
    public static final class TransitionBuilder<C extends Context, V extends Variant<V>> {

        private final V from;
        private final V to;
        private Predicate<C> guard = c -> true;
        private float crossFadeSeconds;
        private Consumer<MuxState<V>> onSwitch;

        TransitionBuilder(V from, V to) {
            this.from = from;
            this.to = to;
        }

        public TransitionBuilder<C, V> when(Predicate<C> guard) {
            this.guard = guard;
            return this;
        }

        public TransitionBuilder<C, V> crossFade(float seconds) {
            this.crossFadeSeconds = seconds;
            return this;
        }

        public TransitionBuilder<C, V> onSwitch(Consumer<MuxState<V>> callback) {
            this.onSwitch = callback;
            return this;
        }

        Transition<C, V> build() {
            return new Transition<>(from, to, guard, crossFadeSeconds, onSwitch);
        }
    }

    //endregion
}
