package lib.kasuga.rendering.models.uml.dynamic.fsm.state;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A {@link StateVar} carrying the two orthogonal <em>parameter-store</em> attributes: {@code externalWritable} (axis A — may external consumers
 * (interaction/controller/redstone) write it, or is it machine-internal only = "derived") and
 * {@code sync} (axis B — is it pushed over the FSM sync channel, or local/decorative only).
 *
 * <p>Identity, type, default, validator, ephemeral semantics, and the typed store behavior are all
 * inherited from {@link StateVar} unchanged: two {@link ParameterSpec}s (or a {@code ParameterSpec}
 * and a plain {@code StateVar}) with the same {@link Id} are equal, so a spec works as the
 * {@code MutableStateMap} key and type-safety is by construction. The two attributes are the only
 * additions.
 *
 * <p>Access control: the container / machine parameter face uses {@code externalWritable()} to reject
 * external {@code set} on derived parameters ({@code StateMachine#set} throws), while machine-internal
 * writers (var provider, action, sync landing) use {@code setInternal} which skips the check. The
 * {@code sync()} flag drives the FSM sync projection ({@code FsmSyncPayload} vars section).
 *
 * <p>Two construction paths:
 * <ul>
 *   <li>{@link #builder(Id, Class, Codec)} — declare a new parameter with explicit attributes.</li>
 *   <li>{@link #of(StateVar)} / {@link #of(StateVar, boolean, boolean)} — wrap an existing var (e.g. a
 *       referenced var from a data-driven definition) with default attributes (external-writable,
 *       non-sync = the pre-parameter-store behavior).</li>
 * </ul>
 */
public final class ParameterSpec<T> extends StateVar<T> {

    private final boolean externalWritable;
    private final boolean sync;

    private ParameterSpec(
            Id id,
            Class<T> type,
            Codec<T> codec,
            T defaultValue,
            Predicate<? super T> validator,
            boolean ephemeral,
            boolean externalWritable,
            boolean sync
    ) {
        super(id, type, codec, defaultValue, validator, ephemeral);
        this.externalWritable = externalWritable;
        this.sync = sync;
    }

    /** Axis A: may external consumers write this parameter? {@code false} = derived (machine-internal writers only). */
    public boolean externalWritable() {
        return externalWritable;
    }

    /** Axis B: is this parameter pushed over the FSM sync channel (server-authoritative)? */
    public boolean sync() {
        return sync;
    }

    /** Wrap an existing var with the default attributes (external-writable, non-sync). */
    public static <T> ParameterSpec<T> of(StateVar<T> var) {
        return of(var, true, false);
    }

    /** Wrap an existing var with explicit attributes; identity stays the var's {@link Id}. */
    public static <T> ParameterSpec<T> of(StateVar<T> var, boolean externalWritable, boolean sync) {
        Objects.requireNonNull(var, "var");
        return new ParameterSpec<>(
                var.id(), var.type(), var.codec(), var.defaultValue(), var.validator(), var.ephemeral(),
                externalWritable, sync);
    }

    /**
     * Start a new parameter declaration. Named {@code parameter} (not {@code builder}) because
     * {@code StateVar.builder} is a static method — a same-signature static with a different return
     * type cannot hide it in a subclass.
     */
    public static <T> Builder<T> parameter(Id id, Class<T> type, Codec<T> codec) {
        return new Builder<>(id, type, codec);
    }

    /** Fluent builder, mirroring {@code StateVar.Builder} plus the two attribute setters. */
    public static final class Builder<T> {

        private final Id id;
        private final Class<T> type;
        private final Codec<T> codec;
        private T defaultValue;
        private Predicate<? super T> validator;
        private boolean ephemeral;
        private boolean externalWritable = true;
        private boolean sync;

        private Builder(Id id, Class<T> type, Codec<T> codec) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
            this.codec = Objects.requireNonNull(codec, "codec");
        }

        public Builder<T> defaultValue(T value) {
            this.defaultValue = value;
            return this;
        }

        public Builder<T> validator(Predicate<? super T> validator) {
            this.validator = validator;
            return this;
        }

        /** Mark this parameter tick-scoped: cleared at the end of every tick (use for triggers). */
        public Builder<T> ephemeral() {
            this.ephemeral = true;
            return this;
        }

        /** {@code false} = derived: external {@code set} is rejected, machine-internal writers only. */
        public Builder<T> externalWritable(boolean externalWritable) {
            this.externalWritable = externalWritable;
            return this;
        }

        /** {@code true} = pushed over the FSM sync channel (server-authoritative value). */
        public Builder<T> sync(boolean sync) {
            this.sync = sync;
            return this;
        }

        public ParameterSpec<T> build() {
            return new ParameterSpec<>(id, type, codec, defaultValue, validator, ephemeral,
                    externalWritable, sync);
        }
    }
}