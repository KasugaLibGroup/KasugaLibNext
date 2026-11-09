package lib.kasuga.registration.data_driven.state;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A typed reference to one actual block-state property of a particular block. */
public final class StateProperty<T extends Comparable<T>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentMap<Key, CopyOnWriteArrayList<Listener<?>>> LISTENERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> CALLBACK_DEPTH = new ThreadLocal<>();

    private final Block block;
    private final Property<T> property;

    private StateProperty(Block block, Property<T> property) {
        this.block = block;
        this.property = property;
    }

    /** Resolve the actual property instance; JSON-created properties are not global constants. */
    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> StateProperty<T> of(Block block, String name, Class<T> valueType) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(valueType, "valueType");
        Property<?> property = block.getStateDefinition().getProperty(name);
        if (property == null) {
            throw new IllegalArgumentException("Block " + block + " has no state property '" + name + "'");
        }
        if (property.getValueClass() != valueType) {
            throw new IllegalArgumentException("Property '" + name + "' has value type "
                    + property.getValueClass().getName() + ", not " + valueType.getName());
        }
        return new StateProperty<>(block, (Property<T>) property);
    }

    public Block block() { return block; }

    public Property<T> property() { return property; }

    public T get(BlockState state) {
        requireBlock(state);
        return state.getValue(property);
    }

    public T get(BlockGetter level, BlockPos pos) {
        return get(level.getBlockState(pos));
    }

    /** Return another immutable state; this does not change the world or fire listeners. */
    public BlockState with(BlockState state, T value) {
        requireBlock(state);
        return state.setValue(property, Objects.requireNonNull(value, "value"));
    }

    /** Change the server world, notifying neighbours and clients using Minecraft's flags 3. */
    public boolean set(ServerLevel level, BlockPos pos, T value) {
        return set(level, pos, value, Block.UPDATE_ALL);
    }

    /**
     * Change the server world with explicit Minecraft update flags. Returns false for a no-op or a
     * rejected world update; true has the same meaning as {@link Level#setBlock(BlockPos, BlockState, int)}.
     */
    public boolean set(ServerLevel level, BlockPos pos, T value, int flags) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Block-state writes must run on the server thread");
        }
        if (CALLBACK_DEPTH.get() != null) {
            throw new IllegalStateException("Cannot change the world from an onChange callback; defer the write");
        }
        BlockState previous = level.getBlockState(pos);
        BlockState next = with(previous, value);
        return next != previous && level.setBlock(pos, next, flags);
    }

    /** Listen on the logical server until the returned subscription is closed. */
    public Subscription onChange(Consumer<Change<T>> callback) {
        return onChange(Side.SERVER, callback);
    }

    /**
     * Listen on the selected logical side. A callback is synchronous and read-only: NeoForge does not
     * permit modifying the level from its state-change hook. Do not write through other APIs either.
     */
    public Subscription onChange(Side side, Consumer<Change<T>> callback) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(callback, "callback");
        Key key = new Key(block, property);
        Listener<T> listener = new Listener<>(side, callback);
        LISTENERS.compute(key, (ignored, existing) -> {
            CopyOnWriteArrayList<Listener<?>> result = existing == null ? new CopyOnWriteArrayList<>() : existing;
            result.add(listener);
            return result;
        });
        return () -> {
            if (!listener.active.compareAndSet(true, false)) return;
            LISTENERS.computeIfPresent(key, (ignored, existing) -> {
                existing.remove(listener);
                return existing.isEmpty() ? null : existing;
            });
        };
    }

    private void requireBlock(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (!state.is(block)) {
            throw new IllegalArgumentException("Property '" + property.getName() + "' belongs to "
                    + block + ", not " + state.getBlock());
        }
    }

    /**
     * Forward the NeoForge block callback here from opted-in custom block classes. Do not invoke it
     * twice if a superclass already forwards it. Placement and removal are intentionally excluded.
     */
    public static void notifyChanges(LevelReader reader, BlockPos pos, BlockState oldState, BlockState newState) {
        if (LISTENERS.isEmpty() || oldState == newState || oldState.getBlock() != newState.getBlock()
                || !(reader instanceof Level level) || level.restoringBlockSnapshots) return;
        for (Property<?> property : newState.getProperties()) {
            CopyOnWriteArrayList<Listener<?>> listeners = LISTENERS.get(new Key(newState.getBlock(), property));
            if (listeners != null) notifyProperty(reader, level.isClientSide, pos, oldState, newState, property, listeners);
        }
    }

    private static <T extends Comparable<T>> void notifyProperty(LevelReader level, boolean clientSide, BlockPos pos,
            BlockState oldState, BlockState newState, Property<T> property, CopyOnWriteArrayList<Listener<?>> listeners) {
        if (!oldState.hasProperty(property)) return;
        T oldValue = oldState.getValue(property);
        T newValue = newState.getValue(property);
        if (oldValue.equals(newValue)) return;
        Change<T> change = new Change<>(level, pos.immutable(), oldState, newState, oldValue, newValue);
        for (Listener<?> raw : listeners) {
            @SuppressWarnings("unchecked") Listener<T> listener = (Listener<T>) raw;
            if (!listener.active.get() || !listener.side.matches(clientSide)) continue;
            Integer depth = CALLBACK_DEPTH.get();
            CALLBACK_DEPTH.set(depth == null ? 1 : depth + 1);
            try {
                listener.callback.accept(change);
            } catch (RuntimeException e) {
                LOGGER.error("Block-state listener for '{}' on {} failed at {}", property.getName(), newState.getBlock(), pos, e);
            } finally {
                if (depth == null) CALLBACK_DEPTH.remove();
                else CALLBACK_DEPTH.set(depth);
            }
        }
    }

    public enum Side {
        SERVER, CLIENT, BOTH;

        private boolean matches(boolean clientSide) {
            return this == BOTH || (clientSide ? this == CLIENT : this == SERVER);
        }
    }

    /** An immutable observation; its level is read-only during the synchronous callback. */
    public record Change<T extends Comparable<T>>(LevelReader level, BlockPos pos,
            BlockState oldState, BlockState newState, T oldValue, T newValue) {}

    /** Closing a subscription is idempotent; the owner is responsible for its lifecycle. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    private record Key(Block block, Property<?> property) {}

    private static final class Listener<T extends Comparable<T>> {
        private final Side side;
        private final Consumer<Change<T>> callback;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Listener(Side side, Consumer<Change<T>> callback) {
            this.side = side;
            this.callback = callback;
        }
    }
}
