package lib.kasuga.rendering.models.uml.dynamic.fsm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The instance bucket: scripting {@link StateMachine} instances by long handle, with a per-id
 * "latest" pointer. Two maps are the single source of truth — {@code byHandle} holds the full
 * (id, machine) record per handle and {@code latestHandleById} points an id at its most recently
 * registered handle; every other view is derived from these.
 *
 * <p>Only scripting machines live here — host-owned machines (block entities / entities) are NOT
 * registered; a host holds its own {@link StateMachine} reference and ticks it directly.
 *
 * <p>Handle semantics: each {@link #register} mints a <b>fresh</b> handle (multi-instance — a
 * re-register does not invalidate prior handles). {@link #release(long)} drops exactly that handle;
 * releasing an id's latest handle also clears the id's latest pointer, so {@link #latest} then
 * returns {@code null} and a later re-register is treated as fresh (no notification). Listeners are
 * notified when an id's latest machine changes (re-register) or is dropped ({@link #removeAll} /
 * {@link #clear}).
 */
public final class FsmMachines {

    /**
     * Notified whenever an id's latest machine is replaced (re-register) or removed
     * ({@link #removeAll} / {@link #clear}) — reserved for hosts that rebuild machines on invalidation.
     */
    public interface InvalidationListener {
        void onInvalidated(Id id);
    }

    /** The full record behind a handle: the machine and the id it was registered under. */
    private record Entry(Id id, StateMachine<?> machine) {}

    private final Map<Long, Entry> byHandle = new ConcurrentHashMap<>();
    private final Map<Id, Long> latestHandleById = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1);
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a runtime machine instance and obtain a scripting handle. Each call mints a <b>fresh</b>
     * handle — re-registering the same id does NOT invalidate prior handles (multi-instance): each handle
     * resolves to its own machine. {@link #latest(Id)} returns the most-recently registered
     * machine for the id. Listeners are notified when an id's latest machine changes.
     */
    public long register(Id id, StateMachine<?> machine) {
        long handle = nextHandle.getAndIncrement();
        byHandle.put(handle, new Entry(id, machine));
        Long previousLatest = latestHandleById.put(id, handle);
        if (previousLatest != null) {
            notifyInvalidated(id);
        }
        return handle;
    }

    /** The machine behind {@code handle} (stays live after a same-id re-register; {@code null} if released). */
    public StateMachine<?> resolve(long handle) {
        Entry entry = byHandle.get(handle);
        return entry == null ? null : entry.machine();
    }

    /** The most-recently registered machine for {@code id} (or {@code null} — also after its latest
     * handle was released). */
    public StateMachine<?> latest(Id id) {
        Long handle = latestHandleById.get(id);
        if (handle == null) {
            return null;
        }
        Entry entry = byHandle.get(handle);
        return entry == null ? null : entry.machine();
    }

    /**
     * Release a single scripting handle. Only that handle is dropped — any other handles for the id
     * remain live. If this was the id's latest handle, the id's latest pointer is cleared (so
     * {@link #latest(Id)} returns {@code null} and a later re-register is treated as fresh).
     */
    public void release(long handle) {
        Entry entry = byHandle.remove(handle);
        if (entry != null) {
            latestHandleById.remove(entry.id(), handle);
        }
    }

    /**
     * Remove every machine registered under {@code id} (the id's latest pointer plus all of its handles);
     * notifies listeners if anything was removed.
     */
    public void removeAll(Id id) {
        boolean hadLatest = latestHandleById.remove(id) != null;
        boolean removedHandles = byHandle.entrySet().removeIf(entry -> id.equals(entry.getValue().id()));
        if (hadLatest || removedHandles) {
            notifyInvalidated(id);
        }
    }

    /** Drop every machine instance; notifies listeners once per distinct id. */
    public void clear() {
        byHandle.values().stream().map(Entry::id).distinct().forEach(this::notifyInvalidated);
        byHandle.clear();
        latestHandleById.clear();
    }

    public void addListener(InvalidationListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyInvalidated(Id id) {
        for (InvalidationListener listener : listeners) {
            listener.onInvalidated(id);
        }
    }
}
