package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reload orchestrator against a stub {@link net.minecraft.server.packs.resources.ResourceManager}
 * ({@link StubResourceManager}): one clear per cycle, the two discovery entries (directory glob and the
 * index's {@code on_reload} array), the interaction between them, cross-entry last-wins, and the paired
 * diagnostics.
 *
 * <p>This is a plain JVM test — no mod jar and no running game; the pack stack is a plain map of
 * virtual files. The wrapper/decode contract itself is locked down in
 * {@code StateMachineDefinitionLoaderTest}, and the clip half of the domain (decode, registration and
 * the post-load reference check) in {@code AnimationClipLoaderTest} / {@code AnimationClipReloadTest}.
 */
class ReloadOrchestratorTest {

    private static final String NS = "reload_test";

    private static final String GOOD_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:good",
                  "layers": [ { "id": "l", "initial_state": "idle",
                    "states": [ { "id": "idle", "duration_ticks": 10 } ] } ] }
              ]
            }
            """;

    private static final String BROKEN_JSON = "{ this is not json }";

    /** The same id twice in one file; the second entry declares a state var, so the winner is identifiable. */
    private static final String DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                { "id": "reload_test:good", "state_vars": [ { "name": "x" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    /** Glob copy of {@code reload_test:dup}: declares one state var, so a glob win is distinguishable. */
    private static final String GLOB_DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:dup", "state_vars": [ { "name": "from_glob" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    /** Index copy of {@code reload_test:dup}: two state vars, so an index win is distinguishable. */
    private static final String INDEX_DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:dup",
                  "state_vars": [ { "name": "from_index_a" }, { "name": "from_index_b" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    // --- fixtures inherited from the pre-orchestrator loader tests (see the preserved tests below) ---

    /** A wrapper file carrying two distinct definitions. */
    private static final String MULTI_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:multi_a", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                { "id": "reload_test:multi_b", "layers": [ { "id": "m", "initial_state": "idle" } ] }
              ]
            }
            """;

    /** Pre-wrapper shape: the top-level object is a definition, not a {@code state_machines} array. */
    private static final String LEGACY_JSON = """
            { "id": "reload_test:legacy", "layers": [ { "id": "l", "initial_state": "idle" } ] }
            """;

    /** A number and a definition missing {@code layers} sit between two good elements. */
    private static final String MIXED_ELEMENTS_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:first", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                5,
                { "id": "reload_test:missing_layers" },
                { "id": "reload_test:last", "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    private static final String EXTRA_KEY_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:extra", "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ],
              "extra": 1
            }
            """;

    private static final String NON_ARRAY_JSON = "{ \"state_machines\": {} }";

    private static final String MISSING_KEY_JSON = "{ \"other\": [] }";

    private FsmDefinitions definitions;

    @BeforeEach
    void freshBucket() {
        definitions = new FsmDefinitions();
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
    }

    @AfterEach
    void clearBucket() {
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
    }

    private ReloadOrchestrator orchestrator() {
        FsmAnimationClips clips = new FsmAnimationClips();
        return new ReloadOrchestrator(new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips));
    }

    private static StubResourceManager manager(String path, String content) {
        return new StubResourceManager().add(NS, path, content);
    }

    private static String indexManifest(String onReloadEntry) {
        return "{ \"on_reload\": [ \"" + onReloadEntry + "\" ] }";
    }

    // --- the reload cycle ---

    @Test
    void loadsValidDefinitionsIntoInjectedBucket() {
        orchestrator().reload(manager("state_machines/good.json", GOOD_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS), "a clean load must not report anything");
    }

    @Test
    void brokenJsonDoesNotAbortTheBatch() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "state_machines/broken.json", BROKEN_JSON)
                .add(NS, "state_machines/good.json", GOOD_JSON));

        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "broken")));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")), "a broken file must not abort the batch");
    }

    @Test
    void reloadReplacesResourceDefinitionsAndKeepsScriptDefinitions() {
        ReloadOrchestrator orchestrator = orchestrator();
        orchestrator.reload(manager("state_machines/good.json", GOOD_JSON));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));

        // a script definition on the same registry must survive reloads
        Id scriptId = Id.fromNamespaceAndPath(NS, "script_def");
        definitions.register(scriptId, definitions.get(Id.fromNamespaceAndPath(NS, "good")));

        // second cycle with an empty pack: RESOURCE definitions go away, SCRIPT stays
        orchestrator.reload(new StubResourceManager());
        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertNotNull(definitions.get(scriptId));
    }

    @Test
    void hashTracksDefinitionIdentityAcrossReloadAndOverwrite() {
        ReloadOrchestrator orchestrator = orchestrator();
        Id good = Id.fromNamespaceAndPath(NS, "good");
        assertEquals(0, definitions.hash(good), "absent id hashes to 0");

        orchestrator.reload(manager("state_machines/good.json", GOOD_JSON));
        int loaded = definitions.hash(good);
        assertNotEquals(0, loaded, "a loaded definition has a non-zero content hash");

        orchestrator.reload(manager("state_machines/good.json", GOOD_JSON));
        assertEquals(loaded, definitions.hash(good), "same content -> same hash");

        definitions.register(good, new StateMachineDefinition(good, List.of(), List.of()));
        assertNotEquals(loaded, definitions.hash(good), "different content -> different hash");
    }

    @Test
    void sameJsonCanBeLoadedTwiceWithoutError() {
        ReloadOrchestrator orchestrator = orchestrator();
        StubResourceManager pack = manager("state_machines/good.json", GOOD_JSON);
        orchestrator.reload(pack);
        orchestrator.reload(pack);
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    @Test
    void duplicateIdWithinFileLastWins() {
        orchestrator().reload(manager("state_machines/dup.json", DUPLICATE_JSON));

        StateMachineDefinition loaded = definitions.get(Id.fromNamespaceAndPath(NS, "good"));
        assertNotNull(loaded, "an id present in the file must be registered");
        assertEquals(1, loaded.stateVars().size(),
                "the later in-file entry wins (the loser declares no state var)");
        assertTrue(errorsContain("Duplicate id 'reload_test:good'"),
                "the superseded in-file entry must be reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    // --- the registration-side assertions the pre-orchestrator loader tests carried ---
    // StateMachineDefinitionLoader used to implement the reload listener and own these assertions.
    // The demotion moved the cycle to the single orchestrator, so they run here instead of being
    // dropped: same assertions, same fixtures, a different owner.

    /** A wrapper file carrying several definitions registers every one of them. */
    @Test
    void wrapperFileRegistersEveryDefinition() {
        orchestrator().reload(manager("state_machines/pair.json", MULTI_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "multi_a")),
                "the first wrapper element must register");
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "multi_b")),
                "the second wrapper element must register");
    }

    /** The pre-wrapper shape is rejected by the decoder, so the orchestrator registers nothing from it. */
    @Test
    void legacyShapeRegistersNothing() {
        orchestrator().reload(manager("state_machines/legacy.json", LEGACY_JSON));

        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "legacy")),
                "a pre-wrapper file must not register anything");
    }

    /** A malformed array element only drops itself; its siblings still register. */
    @Test
    void badElementIsSkippedWhileSiblingsLoad() {
        orchestrator().reload(manager("state_machines/mixed.json", MIXED_ELEMENTS_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "first")),
                "an element before the bad ones must still load");
        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "missing_layers")),
                "a malformed element must be skipped, not registered");
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "last")),
                "an element after the bad ones must still load");
    }

    /**
     * An extra top-level key no longer rejects the file: the known key still decodes and registers, and
     * the unknown key is reported (a shape violation is an isolated diagnostic, not a whole-file
     * rejection). A genuinely broken shape — a non-array value or a missing key — still rejects its own
     * file without aborting the batch.
     */
    @Test
    void extraTopLevelKeyIsReportedButDoesNotRejectTheFile() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "state_machines/extra.json", EXTRA_KEY_JSON)
                .add(NS, "state_machines/non_array.json", NON_ARRAY_JSON)
                .add(NS, "state_machines/missing.json", MISSING_KEY_JSON)
                .add(NS, "state_machines/good.json", GOOD_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "extra")),
                "an extra top-level key must not reject the file's known array");
        assertTrue(errorsContain("unsupported top-level field 'extra'"),
                "but the unknown key must still be reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "non_array")),
                "a non-array value still rejects its own file");
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")),
                "a shape-violating file must not abort the batch");
    }

    // --- the two entries and how they interact ---

    /**
     * A file listed in {@code on_reload} is read once: the glob entry skips it. Reading it twice would
     * collide with itself and re-register the same id.
     */
    @Test
    void globAndIndexListingTheSameFileReadItOnce() {
        AtomicInteger invalidations = new AtomicInteger();
        definitions.addListener(id -> invalidations.incrementAndGet());

        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("state_machines/good.json"))
                .add(NS, "state_machines/good.json", GOOD_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertEquals(0, invalidations.get(),
                "re-registering an id would notify the invalidation listener; the file must be read once");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS), "a single read has no conflict to report");
    }

    /**
     * Two different files declare the same id: the index entry is applied after the glob entry, so the
     * index definition wins and the glob one is never registered (last-wins with no side effect for the
     * loser).
     */
    @Test
    void indexEntryWinsOverTheGlobEntryAndTheLoserIsNotRegistered() {
        AtomicInteger invalidations = new AtomicInteger();
        definitions.addListener(id -> invalidations.incrementAndGet());

        orchestrator().reload(new StubResourceManager()
                .add(NS, "state_machines/a.json", GLOB_DUPLICATE_JSON)
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("content/b.json"))
                .add(NS, "content/b.json", INDEX_DUPLICATE_JSON));

        StateMachineDefinition loaded = definitions.get(Id.fromNamespaceAndPath(NS, "dup"));
        assertNotNull(loaded, "the id must be registered");
        assertEquals(2, loaded.stateVars().size(), "the later (index-listed) definition must win");
        assertEquals(0, invalidations.get(),
                "the losing glob definition must never reach registerResource (it would notify on overwrite)");

        assertTrue(errorsContain("Duplicate id 'reload_test:dup'"),
                "the superseded entry must be recorded: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(errorsContain("data/" + NS + "/state_machines/a.json")
                        && errorsContain("data/" + NS + "/content/b.json"),
                "the conflict must name both source files: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    /** {@link ReloadOrchestrator#indexResourcePath} derives the pack-stack path from the canonical segments. */
    @Test
    void indexResourcePathMatchesTheCanonicalIndexLayout() {
        assertEquals("kasuga_lib/data_driven", ReloadOrchestrator.indexResourcePath("kasuga_lib"));
        assertEquals("kasuga_lib/data_driven", ReloadOrchestrator.indexResourcePath("kuayue"),
                "the index directory is the same for every mod; only the data/<ns> prefix is implicit");
        assertTrue(JsonTreeBuilder.indexDirectorySegments("kasuga_lib")[2].equals("kasuga_lib")
                        && JsonTreeBuilder.indexDirectorySegments("kasuga_lib")[3].equals("data_driven"),
                "the derivation must reuse the shared canonical segments");
    }

    // --- diagnostics ---

    @Test
    void decodeDiagnosticsAreRecordedWithTheFileInTheMessage() {
        orchestrator().reload(manager("state_machines/bad.json", "{ \"state_machines\": {} }"));

        List<Throwable> errors = Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS);
        assertFalse(errors.isEmpty(), "a decode failure must land in the bucket");
        assertTrue(errorsContain("data/" + NS + "/state_machines/bad.json"),
                "the diagnostic must locate the file: " + errors);
    }

    /** Registration content listed in on_reload is reported with the symmetric hint toward on_register. */
    @Test
    void registrationContentListedUnderOnReloadIsHintedAtOnRegister() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("content/blocks.json"))
                .add(NS, "content/blocks.json", "{ \"blocks\": [] }"));

        assertTrue(errorsContain("unsupported top-level field 'blocks'"),
                "the offending field must be named: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(errorsContain("'on_register'"),
                "the hint must point at the index's other array: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    /**
     * {@code animation_clips} is a reload-domain key with a consumer: an empty array loads cleanly (and
     * the clip half of the domain is covered by {@code AnimationClipReloadTest}).
     */
    @Test
    void animationClipsKeyIsRecognisedAndConsumed() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("content/clips.json"))
                .add(NS, "content/clips.json", "{ \"animation_clips\": [] }"));

        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS),
                "a known reload-domain key must not be reported as an unsupported field");
    }

    /** A manifest path that fails the shared path contract is reported and skipped. */
    @Test
    void invalidIndexPathIsReportedAndSkipped() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("../escape.json")));

        assertTrue(errorsContain("Invalid 'on_reload' path '../escape.json'"),
                "the path contract must be enforced on this side too: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    private static boolean errorsContain(String needle) {
        return Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).stream()
                .anyMatch(error -> error.getMessage() != null && error.getMessage().contains(needle));
    }
}
