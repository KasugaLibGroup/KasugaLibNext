package lib.kasuga.registration.data_driven.reload;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * The reload-domain extension point, symmetric to {@link lib.kasuga.registration.data_driven.TypeHandler}
 * on the registration side: where a {@code TypeHandler} owns one registration type's parse / apply, a
 * {@code ReloadHandler} owns one reload type's decode / register.
 *
 * <p>The reload orchestrator ({@code ReloadOrchestrator}) discovers content files, deduplicates their
 * entries with the shared last-wins resolver and then calls one handler per top-level key. Before this
 * interface existed the orchestrator hard-coded the two types it knew
 * ({@code state_machines}, {@code animation_clips}) in five places; a handler now replaces all of them,
 * so adding a reload type is a registration, not an edit to the orchestrator.
 *
 * <p><strong>Lifecycle per reload cycle</strong> (all handlers in registry order):
 * <ol>
 *   <li>{@link #clearBucket()} — once per cycle, before any discovery;</li>
 *   <li>{@link #decode(JsonObject)} for every file whose top-level keys include {@link #typeName()};</li>
 *   <li>{@link #register(Object)} for every surviving (last-wins) entry, once discovery is complete;</li>
 *   <li>{@link #afterReload(List)} — only after <em>every</em> handler finished its register phase, so a
 *       cross-type check (e.g. a state machine's dangling clip reference) sees the other handler's
 *       registrations. Running it per handler right after that handler's own register would judge the
 *       references before the clips landed and misreport every one of them.</li>
 * </ol>
 *
 * <p>{@link #globDirectories()} names the directories discovered by directory glob in addition to the
 * index's {@code on_reload} arrays. The default is index-only: a type whose files no manifest lists is
 * never read. A glob-discovered file that the index already lists is skipped by the orchestrator (it
 * is read once, through the index), so an implementation never has to deduplicate the two entries
 * itself.
 *
 * <p>Implementations are stateless beyond the buckets they own; a handler is registered once (typically
 * from a {@code @Context} bean's {@code @PostConstruct}) and reused for the process's lifetime.
 *
 * @param <T> the decoded payload type this handler registers
 */
public interface ReloadHandler<T> {

    /**
     * The top-level JSON key this handler consumes, e.g. {@code "state_machines"}. Doubles as the
     * handler's identity in {@link ReloadHandlerRegistry} and as the collision space for duplicate
     * resolution, so two types with equal ids never collide.
     *
     * @return the top-level key
     */
    String typeName();

    /**
     * Noun describing a content file of this type, used in decode diagnostics, e.g.
     * {@code "state machine file"} to yield {@code "Failed to decode state machine file '<path>'"}. The
     * default is generic so a handler that does not care about the wording works unchanged; a handler
     * whose diagnostics are locked by tests overrides it to preserve the exact message.
     *
     * @return the file noun used in diagnostics
     */
    default String describeFile() {
        return "reload-domain file";
    }

    /**
     * Directories under {@code data/<ns>/} whose {@code *.json} files are discovered by directory glob
     * in addition to the index's {@code on_reload} arrays. The default declares none — the type is
     * index-only. A returned set is iterated in its own order, so a handler with several glob
     * directories should return an ordered set to keep its discovery deterministic.
     *
     * @return the glob directories, or an empty set for an index-only type
     */
    default Set<String> globDirectories() {
        return Set.of();
    }

    /**
     * Drops everything this handler loaded from resources, called exactly once per reload cycle before
     * discovery. Code/script-owned entries must survive ("script wins"), so an implementation delegates
     * to its bucket's resource-only clear rather than a full wipe.
     *
     * <p>A clear that throws is isolated to this handler: the orchestrator records it as a diagnostic
     * and still clears and populates every other handler, so one broken implementation cannot leave the
     * rest of the cycle's buckets half-cleared.
     */
    void clearBucket();

    /**
     * Decodes one content file. Implementations must not read the file — the orchestrator owns I/O
     * and the per-file guard — and must not throw for malformed content: a shape violation is returned
     * as an {@link Decoded#errors()} entry so the orchestrator can pair it with a log and a diagnostic
     * bucket. A decode that decides the file is not its shape (e.g. a decoder that rejects a mixed file)
     * returns the diagnostics for it and no entries.
     *
     * @param root the parsed file body, already known to be a JSON object
     * @return the decoded entries plus any diagnostics
     */
    Decoded<T> decode(JsonObject root);

    /**
     * Registers one entry that survived duplicate resolution. Called after the whole cycle's discovery
     * is complete, so the bucket is fully populated before any cross-type check runs. An implementation
     * should throw on a registration failure (the orchestrator isolates and attributes it) rather than
     * swallowing it.
     *
     * @param payload the decoded value
     */
    void register(T payload);

    /**
     * Runs after <em>every</em> handler's register phase, over exactly the entries this cycle registered.
     * This is where a cross-type validation that needs another handler's bucket belongs — the clip
     * reference check runs here precisely because it must judge against the clip bucket after the clip
     * handler has populated it.
     *
     * @param registered the entries this cycle registered, each with the namespace it came from
     */
    default void afterReload(List<Reloaded<T>> registered) {}
}
