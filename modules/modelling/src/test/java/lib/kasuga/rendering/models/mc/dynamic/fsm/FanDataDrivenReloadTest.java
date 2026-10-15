package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end exercise of the real data-driven fan files through the reload orchestrator — the first
 * time the reload pipeline runs against actual shipped content rather than the in-test stubs of
 * {@code ReloadOrchestratorTest} / {@code AnimationClipReloadTest}.
 *
 * <p>The three files are read from the working tree by {@link ModellingContentFiles}: the index
 * manifest (both domains), the {@code on_reload} clip file and the glob-discovered state machine file.
 * The test asserts the full chain — index → clip bucket → definition → clip reference resolves — with
 * no diagnostics.
 */
class FanDataDrivenReloadTest {

    private static final String NS = "kasuga_lib";

    private static final Id CLIP_ID = Id.fromNamespaceAndPath(NS, "fan_fsm_data_driven");

    private static final Id MACHINE_ID = Id.fromNamespaceAndPath(NS, "fan_machine_data_driven");

    @BeforeEach
    void freshBucket() {
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
    }

    @AfterEach
    void clearBucket() {
        JsonTreeBuilder.clearLoadingErrors(NS);
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
    }

    /** The shipped manifest really declares both domains, at the canonical paths. */
    @Test
    void indexManifestDeclaresBothDomains() {
        JsonObject root = ModellingContentFiles.readJson(ModellingContentFiles.INDEX).getAsJsonObject();
        JsonTreeBuilder.IndexManifest manifest = JsonTreeBuilder.parseIndexManifest(NS, "modelling.json", root);

        assertTrue(manifest.declaredOnRegister(), "the fan block content file must be on_register");
        assertTrue(manifest.declaredOnReload(), "the fan clip file must be on_reload");
        assertEquals(List.of(
                        "kasuga_lib_content/fan_formula_data_driven.json",
                        "kasuga_lib_content/fsm_blocks.json"),
                manifest.onRegisterPaths(),
                "the on_register domain must carry both the fan block content and the FSM block content");
        assertEquals(List.of("animation_clips/fan_fsm_data_driven.json"), manifest.onReloadPaths());
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(NS), "the manifest must be schema-clean");
    }

    /** Index + clip + machine, all read from the shipped files: the machine's clip reference resolves. */
    @Test
    void realReloadFilesLoadEndToEndWithoutDiagnostics() {
        FsmDefinitions definitions = new FsmDefinitions();
        FsmAnimationClips clips = new FsmAnimationClips();

        new ReloadOrchestrator(new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips))
                .reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/modelling.json", ModellingContentFiles.read(ModellingContentFiles.INDEX))
                .add(NS, "animation_clips/fan_fsm_data_driven.json", ModellingContentFiles.read(ModellingContentFiles.CLIP))
                .add(NS, "state_machines/fan_machine_data_driven.json", ModellingContentFiles.read(ModellingContentFiles.MACHINE)));

        FsmAnimationClips.Entry entry = clips.get(CLIP_ID);
        assertNotNull(entry, "the on_reload clip file must register 'kasuga_lib:fan_fsm_data_driven'");
        assertEquals(FsmAnimationClips.ClipSource.RESOURCE, entry.source(),
                "a file clip belongs to the reload bucket, not the script one");

        StateMachineDefinition definition = definitions.get(MACHINE_ID);
        assertNotNull(definition, "the directory glob must discover the state machine file");
        assertEquals(List.of(), DefinitionStateMachineFactory.unknownClipReferences(definition, clips),
                "the machine's states[].clip must resolve against the file-loaded clip");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS),
                "a clean end-to-end load reports nothing");
    }
}
