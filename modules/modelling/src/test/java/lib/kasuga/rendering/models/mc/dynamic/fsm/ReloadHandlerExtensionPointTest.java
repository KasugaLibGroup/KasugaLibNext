package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.registration.data_driven.reload.Reloaded;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the reload domain has a real extension point: a reload type that the orchestrator has never
 * heard of — registered from a test through {@link ReloadHandlerRegistry}, with no framework class
 * modified — is dispatched, deduplicated, registered and given its after-phase exactly like the two
 * built-in FSM types.
 *
 * <p>It is a plain JVM test over the same {@link StubResourceManager} pack stack the other reload
 * tests use. The fake handler ({@value #TYPE}) is registered into the shared registry before the
 * orchestrator is built, so it shares the registry order with the built-in handlers; the tests then
 * assert the properties the refactor promised: dynamic known fields, per-handler last-wins, one clear
 * before discovery, and the two-phase register/afterReload order that keeps the clip check correct.
 */
class ReloadHandlerExtensionPointTest {

    private static final String NS = "fake_reload_test";

    private static final String TYPE = "fake_things";

    private FsmDefinitions definitions;

    private FsmAnimationClips clips;

    @BeforeEach
    void freshBuckets() {
        definitions = new FsmDefinitions();
        clips = new FsmAnimationClips();
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
    }

    @AfterEach
    void resetRegistry() {
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
        // The fixture handler must not leak into any other test's registry.
        ReloadHandlerRegistry.clearForTest();
    }

    /**
     * A content file of an unknown-to-the-orchestrator type is dispatched by the registry, not rejected
     * as an unsupported field, and its entries deduplicate with last-wins.
     */
    @Test
    void fakeTypeIsDispatchedDeduplicatedAndRegistered() {
        FakeHandler fake = new FakeHandler(TYPE, clips);
        ReloadHandlerRegistry.register(fake);
        new ReloadOrchestrator(new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips))
                .reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        "{ \"on_reload\": [ \"content/fake.json\" ] }")
                .add(NS, "content/fake.json", "{ \"fake_things\": [ "
                        + "{ \"id\": \"fake_reload_test:thing\", \"note\": \"loser\" }, "
                        + "{ \"id\": \"fake_reload_test:thing\", \"note\": \"winner\" } ] }"));

        assertEquals(2, fake.decoded.size(), "both entries of the fake type must decode");
        assertEquals(List.of("winner"), fake.registered,
                "only the last-wins winner must register (the loser has no side effect)");
        assertTrue(fake.cleared, "the handler's bucket must be cleared once for the cycle");
        assertTrue(errorsContain("Duplicate id 'fake_reload_test:thing'"),
                "the superseded entry must be reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(!errorsContain("unsupported top-level field 'fake_things'"),
                "a registered type's key is a known field, not an unsupported one: "
                        + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    /**
     * The built-in types keep working next to the fake one: a file mixing a known registration-shaped
     * key with a truly unknown key reports only the unknown key.
     */
    @Test
    void unknownKeysAreStillReportedWhileRegisteredKeysAreKnown() {
        ReloadHandlerRegistry.register(new FakeHandler(TYPE, clips));
        new ReloadOrchestrator(new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips))
                .reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", "{ \"on_reload\": [ \"content/mixed.json\" ] }")
                .add(NS, "content/mixed.json", "{ \"fake_things\": [], \"bogus\": [] }"));

        assertTrue(errorsContain("unsupported top-level field 'bogus'"),
                "a genuinely unknown key must still be reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(!errorsContain("unsupported top-level field 'fake_things'"),
                "'fake_things' is registered, so it must not be reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    /**
     * The timing contract: <em>every</em> handler registers before <em>any</em> handler's after-phase
     * runs. The fake handler is registered first, so it is the first to run its after-phase — if the
     * phases were interleaved, the clip handler (registered after it) would not have loaded the clip
     * yet. Seeing the clip in the fake handler's after-phase is therefore only possible under the
     * two-phase order.
     */
    @Test
    void everyHandlerRegistersBeforeAnyHandlerRunsItsAfterPhase() {
        String clipId = NS + ":clip";
        FakeHandler fake = new FakeHandler(TYPE, clips);
        ReloadHandlerRegistry.clearForTest();
        ReloadHandlerRegistry.register(fake); // first: the fake one must observe the later clip handler
        new ReloadOrchestrator(new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips))
                .reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        "{ \"on_reload\": [ \"content/fake.json\", \"content/clip.json\" ] }")
                .add(NS, "content/fake.json", "{ \"fake_things\": [ { \"id\": \"" + NS + ":thing\" } ] }")
                .add(NS, "content/clip.json", "{ \"animation_clips\": [ { \"id\": \"" + clipId + "\" } ] }"));

        assertEquals(1, fake.afterReloadCalls, "the fake handler's after-phase must run exactly once");
        assertNotNull(fake.clipSeenInAfterPhase,
                "the fake after-phase ran before the clip handler registered: the clip map was "
                        + "still empty, so register/afterReload were interleaved");
        assertEquals(Id.parse(clipId), fake.clipSeenInAfterPhase,
                "the clip registered by the later handler must be visible in the fake after-phase");
    }

    /**
     * A minimal fake handler: decodes {@code {"fake_things": [ {"id": ..., "note": ...} ]}}, records
     * what the orchestrator calls and when. It is never registered by production code, so it also
     * proves the extension point needs no framework change.
     */
    private static final class FakeHandler implements ReloadHandler<String> {

        private final String typeName;
        private final FsmAnimationClips clips;

        private final List<String> decoded = new ArrayList<>();
        private final List<String> registered = new ArrayList<>();
        private boolean cleared;
        private int afterReloadCalls;
        private Id clipSeenInAfterPhase;

        private FakeHandler(String typeName, FsmAnimationClips clips) {
            this.typeName = typeName;
            this.clips = clips;
        }

        @Override
        public String typeName() {
            return typeName;
        }

        @Override
        public Set<String> globDirectories() {
            return Set.of();
        }

        @Override
        public void clearBucket() {
            cleared = true;
        }

        @Override
        public Decoded<String> decode(JsonObject root) {
            List<Decoded.Entry<String>> entries = new ArrayList<>();
            JsonElement body = root.get(typeName);
            for (JsonElement element : body.getAsJsonArray()) {
                JsonObject object = element.getAsJsonObject();
                String id = object.get("id").getAsString();
                String note = object.has("note") ? object.get("note").getAsString() : "";
                decoded.add(note);
                entries.add(new Decoded.Entry<>(id, note));
            }
            return new Decoded<>(entries, List.of());
        }

        @Override
        public void register(String payload) {
            registered.add(payload);
        }

        @Override
        public void afterReload(List<Reloaded<String>> reloaded) {
            afterReloadCalls++;
            // Record whatever clip is visible now; under the two-phase order every clip handler has
            // already run, under an interleaved order this would still be empty.
            clipSeenInAfterPhase = clips == null ? null : firstClipId();
        }

        private Id firstClipId() {
            Id candidate = Id.parse(NS + ":clip");
            return clips.get(candidate) != null ? candidate : null;
        }
    }

    private static boolean errorsContain(String needle) {
        return Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).stream()
                .anyMatch(error -> error.getMessage() != null && error.getMessage().contains(needle));
    }
}
