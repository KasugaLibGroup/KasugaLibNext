package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Blender;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.PoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code animation_clips} half of the reload domain, end to end and pure JVM: an index-listed clip
 * file plus a state machine file that references it are fed to {@link ReloadOrchestrator} through a stub
 * pack stack, and the assertions follow the whole chain — the clip lands in
 * {@link FsmAnimationClips}'s reload bucket, the definition's clip reference resolves (so the
 * build-time check no longer reports an unknown clip), and the machine built from the loaded definition
 * actually samples the clip into the pose sink.
 *
 * <p>Also locks the properties the wiring must not lose: clips are index-only (no directory glob),
 * last-wins across clip files, script wins over a file clip of the same id, a second cycle re-plays
 * cleanly, and the post-load reference check reports a dangling clip without blocking the definition.
 */
class AnimationClipReloadTest {

    private static final String NS = "clip_reload_test";

    private static final String CLIP_ID = NS + ":wheel";

    private static final String MACHINE_ID = NS + ":machine";

    private static final float DT = 1f / 20f;

    /** A clip file with one bone track, so the loaded clip can be observed through the machine. */
    private static final String CLIP_FILE = clipsFile("""
            { "id": "clip_reload_test:wheel",
              "duration_seconds": 2.0,
              "bones": [ { "bone": "root", "keyframes": [
                  { "time": 0.0, "transform": { "rotate": [0, 0, 0] } },
                  { "time": 1.0, "transform": { "rotate": [0, 180, 0] } } ] } ] }
            """);

    /** The wrapper file of a machine whose only state plays {@link #CLIP_ID}. */
    private static final String MACHINE_FILE = machineFile(CLIP_ID);

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
    void clearBucket() {
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
    }

    private ReloadOrchestrator orchestrator() {
        return new ReloadOrchestrator(new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips));
    }

    // --- the chain: index -> clip bucket -> definition -> machine -> pose ---

    /** The whole point of the stage: a file clip is registered, resolves, and animates. */
    @Test
    void indexListedClipIsRegisteredAndPlayedByTheMachineThatReferencesIt() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        index("content/clips.json", "state_machines/machine.json"))
                .add(NS, "content/clips.json", CLIP_FILE)
                .add(NS, "state_machines/machine.json", MACHINE_FILE));

        FsmAnimationClips.Entry entry = clips.get(Id.parse(CLIP_ID));
        assertNotNull(entry, "an index-listed clip file must register its clip");
        assertEquals(FsmAnimationClips.ClipSource.RESOURCE, entry.source(),
                "a file clip belongs to the reload bucket");
        assertTrue(entry.data() instanceof AnimationClip, "the decoded data is the clip itself");
        assertEquals(2.0f, ((AnimationClip) entry.data()).durationSeconds(), 1e-4f);
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS), "a clean cycle reports nothing");

        StateMachineDefinition definition = definitions.get(Id.parse(MACHINE_ID));
        assertNotNull(definition, "the state machine file listed next to the clip must load too");
        assertEquals(List.of(), DefinitionStateMachineFactory.unknownClipReferences(definition, clips),
                "the build-time check must find no unknown clip once the clip file was loaded");

        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = new DefinitionStateMachineFactory<Object>(
                new FsmFunctionLibrary(), new StateVarRegistry(), clips).build(new Object(), definition, sink);

        assertTrue(machine.layer("l").active().hasClip(),
                "the state must actually carry the clip instead of degrading to its static pose");
        machine.tick(DT);
        machine.tick(DT);
        assertTrue(sink.last.bones().containsKey("root"), "the clip pose must reach the sink");
        assertTrue(sink.last.bones().get("root").base.getRotation().angle() > 0f,
                "the file-loaded clip must be sampled, not left at the identity");
    }

    // --- the post-load reference check ---

    /** A definition with no clip file behind it is reported at reload end, and still loads. */
    @Test
    void missingClipFileIsReportedAfterTheLoad() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", index("state_machines/machine.json"))
                .add(NS, "state_machines/machine.json", MACHINE_FILE));

        assertNotNull(definitions.get(Id.parse(MACHINE_ID)),
                "a dangling clip reference must not block the definition (degradation is unchanged)");
        assertNull(clips.get(Id.parse(CLIP_ID)), "no clip file was listed, so nothing registered");
        assertTrue(errorsContain(MACHINE_ID), "the report must name the definition: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(errorsContain(CLIP_ID), "the report must name the dangling clip id: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    /** A clip file that exists but declares a different id leaves the reference just as dangling. */
    @Test
    void wrongClipIdInTheClipFileIsReported() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        index("content/clips.json", "state_machines/machine.json"))
                .add(NS, "content/clips.json", clipsFile(clipElement(NS + ":another_clip", 1f)))
                .add(NS, "state_machines/machine.json", MACHINE_FILE));

        assertNotNull(clips.get(Id.parse(NS + ":another_clip")), "the declared clip itself loads fine");
        assertNull(clips.get(Id.parse(CLIP_ID)));
        assertTrue(errorsContain(MACHINE_ID) && errorsContain(CLIP_ID),
                "the dangling reference must be reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    // --- source semantics ---

    /** A SCRIPT clip of the same id is not clobbered by the file version (script wins). */
    @Test
    void scriptClipWinsOverTheFileVersion() {
        Object scriptData = new Object();
        clips.register(Id.parse(CLIP_ID), ClipSampler.INSTANCE, scriptData);

        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", index("content/clips.json"))
                .add(NS, "content/clips.json", CLIP_FILE));

        FsmAnimationClips.Entry entry = clips.get(Id.parse(CLIP_ID));
        assertSame(scriptData, entry.data(), "the file version must not shadow the script clip");
        assertEquals(FsmAnimationClips.ClipSource.SCRIPT, entry.source());
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS), "a refused file write is not an error");
    }

    /** The second cycle clears the reload bucket and re-plays the file clips without leftovers. */
    @Test
    void secondCycleReplaysClipFilesWithoutLeftovers() {
        ReloadOrchestrator orchestrator = orchestrator();
        orchestrator.reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", index("content/clips.json"))
                .add(NS, "content/clips.json", clipsFile(clipElement(CLIP_ID, 1f),
                        clipElement(NS + ":spare", 1f))));
        assertNotNull(clips.get(Id.parse(CLIP_ID)));
        assertNotNull(clips.get(Id.parse(NS + ":spare")));

        orchestrator.reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", index("content/clips.json"))
                .add(NS, "content/clips.json", clipsFile(clipElement(CLIP_ID, 3f))));

        FsmAnimationClips.Entry entry = clips.get(Id.parse(CLIP_ID));
        assertNotNull(entry, "the replayed clip must be back");
        assertEquals(3f, ((AnimationClip) entry.data()).durationSeconds(), 1e-4f, "the new file version wins");
        assertNull(clips.get(Id.parse(NS + ":spare")), "the clip dropped from the file must not linger");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS), "a replay is not a duplicate conflict");
    }

    // --- discovery and conflict semantics ---

    /** Clips are index-only: {@code animation_clips/} is not a glob directory. */
    @Test
    void animationClipsDirectoryIsNotGlobDiscovered() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "animation_clips/orphan.json", CLIP_FILE));

        assertNull(clips.get(Id.parse(CLIP_ID)),
                "a clip file no manifest lists must not be read (there is no animation_clips/ glob)");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS), "and an unlisted file is not an error either");
    }

    /** Two clip files declaring the same id: the later one wins, and the conflict is reported. */
    @Test
    void laterClipFileWinsForTheSameId() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", index("content/a.json", "content/b.json"))
                .add(NS, "content/a.json", clipsFile(clipElement(CLIP_ID, 1f)))
                .add(NS, "content/b.json", clipsFile(clipElement(CLIP_ID, 9f))));

        assertEquals(9f, ((AnimationClip) clips.get(Id.parse(CLIP_ID)).data()).durationSeconds(), 1e-4f,
                "the later file wins (last-wins)");
        assertTrue(errorsContain("Duplicate id '" + CLIP_ID + "'"),
                "the superseded entry must be recorded: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(errorsContain("content/a.json") && errorsContain("content/b.json"),
                "the conflict must name both source files: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    /**
     * A file carrying both known top-level keys is consumed by both handlers in one pass: each handler
     * reads its own array and ignores the other key, so the definition and the clip both register —
     * one file may hold several types. The machine references the clip in the same file, so the
     * post-load reference check must also pass.
     */
    @Test
    void mixedTopLevelKeysAreConsumedByBothHandlers() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", index("content/mixed.json"))
                .add(NS, "content/mixed.json", "{ \"state_machines\": [ " + machineElement(CLIP_ID) + " ], "
                        + "\"animation_clips\": [ " + clipElement(CLIP_ID, 1f) + " ] }"));

        assertNotNull(definitions.get(Id.parse(MACHINE_ID)), "the definition half must load from the mixed file");
        assertNotNull(clips.get(Id.parse(CLIP_ID)), "the clip half must load from the same mixed file");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS),
                "both keys are known, so nothing is reported: " + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
    }

    // --- fixtures ---

    /** One {@code on_reload} manifest listing the given content paths, in array order. */
    private static String index(String... reloadPaths) {
        return "{ \"on_reload\": [ "
                + String.join(", ", java.util.Arrays.stream(reloadPaths).map(p -> "\"" + p + "\"").toList())
                + " ] }";
    }

    private static String clipsFile(String... elements) {
        return "{ \"animation_clips\": [ " + String.join(", ", elements) + " ] }";
    }

    /** A minimal clip element: the id is required, everything else has a default. */
    private static String clipElement(String id, float durationSeconds) {
        return "{ \"id\": \"" + id + "\", \"duration_seconds\": " + durationSeconds + " }";
    }

    /** A machine wrapper file whose single state plays {@code clipId}. */
    private static String machineFile(String clipId) {
        return "{ \"state_machines\": [ " + machineElement(clipId) + " ] }";
    }

    private static String machineElement() {
        return machineElement("clip_reload_test:unused");
    }

    private static String machineElement(String clipId) {
        return """
                { "id": "clip_reload_test:machine",
                  "layers": [ { "id": "l", "mode": "base", "initial_state": "spin",
                    "states": [ { "id": "spin", "clip": { "id": "%s", "loop": true } } ],
                    "transitions": [] } ] }
                """.formatted(clipId);
    }

    private static boolean errorsContain(String needle) {
        return Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).stream()
                .anyMatch(error -> error.getMessage() != null && error.getMessage().contains(needle));
    }

    /** Captures the last flushed pose, so a sampled clip is observable. */
    private static final class RecordingSink implements PoseSink {

        Blender last;

        @Override
        public void apply(Blender blender) {
            last = blender;
        }
    }
}
