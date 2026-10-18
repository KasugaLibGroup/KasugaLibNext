package lib.kasuga.registration.data_driven.reload;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The reload-domain counterpart of {@link lib.kasuga.registration.data_driven.TypeHandlerRegistry}: a
 * static, insertion-ordered table of {@link ReloadHandler}s by {@link ReloadHandler#typeName()}.
 *
 * <p>Order is load-bearing. The orchestrator iterates {@link #all()} to decide both the dispatch order
 * and the register order, which together determine last-wins: a later handler's entry for an id is
 * judged against the earlier handlers' buckets exactly as the resource manager's file order would. A
 * {@link LinkedHashMap} preserves the order handlers were registered in; callers that need a specific
 * order (e.g. state machines before clips) must register in that order.
 *
 * <p>Registration is idempotent by type name — re-registering the same {@code typeName} replaces the
 * previous handler in place, keeping its original position. This lets a {@code @Context} bean re-run
 * safely in tests without duplicating keys.
 *
 * <p>Unlike its registration-side sibling, this table exposes {@link #clearForTest()}: the absence of
 * an equivalent hook on {@code TypeHandlerRegistry} means a test that registers a handler leaks it into
 * every later test in the same JVM, and was recorded as a pitfall in the design. This table is new, so
 * the hook is present from the start; production code never calls it.
 */
public final class ReloadHandlerRegistry {

    private static final Map<String, ReloadHandler<?>> HANDLERS = new LinkedHashMap<>();

    /**
     * Registers a handler under its {@link ReloadHandler#typeName()}. A second registration for the
     * same type name replaces the first while keeping its position in iteration order.
     *
     * @param handler the handler to register; {@code null} is ignored
     */
    public static void register(ReloadHandler<?> handler) {
        if (handler == null) {
            return;
        }
        HANDLERS.put(handler.typeName(), handler);
    }

    /** The handler registered for {@code typeName}, or {@code null} when none is. */
    public static ReloadHandler<?> get(String typeName) {
        return HANDLERS.get(typeName);
    }

    /** Every registered handler, in registration order. */
    public static Collection<ReloadHandler<?>> all() {
        return Collections.unmodifiableCollection(HANDLERS.values());
    }

    /**
     * Drops every registered handler. Reserved for tests that install a fixture handler and must not
     * leak it into later tests; production code leaves the table populated for the process lifetime.
     */
    public static void clearForTest() {
        HANDLERS.clear();
    }

    private ReloadHandlerRegistry() {}
}
