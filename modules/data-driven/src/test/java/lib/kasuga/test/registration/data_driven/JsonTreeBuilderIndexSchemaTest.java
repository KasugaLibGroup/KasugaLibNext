package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder.IndexManifest;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder.ResolvedIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table test for the index-manifest schema rules {@code JsonTreeBuilder} enforces: the top-level
 * {@code on_register} / {@code on_reload} arrays (SDD D1–D6). Every rule maps to a distinct,
 * deterministic diagnostic, and — since the loader must never silently drop a declaration — each test
 * also pins which entries a domain may still consume.
 *
 * <p>Both {@link JsonTreeBuilder#parseIndexManifest(String, String, JsonObject)} and
 * {@link JsonTreeBuilder#resolveIndexManifests(String, List)} are the in-memory halves of the index
 * pipeline, so this is a plain JVM test — no mod jar, no NeoForge runtime.
 */
class JsonTreeBuilderIndexSchemaTest {

    private static final String MOD = "index_schema_mod";

    @AfterEach
    void clearBuckets() {
        JsonTreeBuilder.clearLoadingErrors();
    }

    // --- D1: at least one of the two fields must be declared ---------------------------------------

    @Test
    void declaringNeitherDomainIsReportedAndConsumesNothing() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        IndexManifest manifest = parse("{}");

        assertFalse(manifest.declaredOnRegister());
        assertFalse(manifest.declaredOnReload());
        assertEquals(List.of(), manifest.onRegisterPaths(), "nothing may be consumed from a bare manifest");
        assertEquals(List.of(), manifest.onReloadPaths());
        assertErrorCount(1);
        assertTrue(errors().get(0).getMessage().contains("declares neither 'on_register' nor 'on_reload'"),
                errors().toString());
    }

    @Test
    void emptyFileIsReportedInsteadOfSilentlyIgnored() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        // GSON turns an empty document into null; the parser must treat that as "declared nothing".
        IndexManifest manifest = JsonTreeBuilder.parseIndexManifest(MOD, "empty.json", null);

        assertFalse(manifest.declaredOnRegister());
        assertFalse(manifest.declaredOnReload());
        assertErrorCount(1);
        String message = errors().get(0).getMessage();
        assertTrue(message.contains("empty.json"), "the empty file must be named: " + message);
        assertTrue(message.contains("declares neither 'on_register' nor 'on_reload'"), message);
    }

    // --- D2: an empty array is valid and counts as declared ---------------------------------------

    @Test
    void emptyArrayIsValidAndCountsAsDeclared() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        IndexManifest manifest = parse("{\"on_register\": []}");

        assertTrue(manifest.declaredOnRegister(), "an empty array still declares the domain");
        assertFalse(manifest.declaredOnReload());
        assertEquals(List.of(), manifest.onRegisterPaths());
        assertEquals(List.of(), errors(), "an empty declared array is not an error: " + errors());

        // The aggregate stage must not treat "declared but empty" as "nothing declared" (D4).
        ResolvedIndex index = JsonTreeBuilder.resolveIndexManifests(MOD, List.of(manifest));
        assertFalse(index.nothingDeclared());
        assertEquals(List.of(), errors());
    }

    // --- D3: a malformed field never aborts its sibling -------------------------------------------

    @Test
    void nonArrayFieldDoesNotAbortTheSibling() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        // A broken sibling on the right: the two valid on_register entries must be kept.
        IndexManifest manifest = parse("{\"on_register\": [\"a.json\", \"b.json\"], \"on_reload\": 7}");
        assertTrue(manifest.declaredOnRegister());
        assertFalse(manifest.declaredOnReload());
        assertEquals(2, manifest.onRegisterPaths().size(),
                "both on_register entries survive a broken sibling: " + manifest);
        assertErrorCount(1);
        assertTrue(errors().get(0).getMessage().contains("'on_reload' must be an array"), errors().toString());

        // ...and the mirror image: a broken on_register must not stop on_reload.
        JsonTreeBuilder.clearLoadingErrors(MOD);
        IndexManifest mirrored = parse("{\"on_register\": \"nope\", \"on_reload\": [\"r.json\"]}");
        assertFalse(mirrored.declaredOnRegister());
        assertTrue(mirrored.declaredOnReload());
        assertEquals(List.of("r.json"), mirrored.onReloadPaths(),
                "the sibling on_reload entry must still be consumed: " + mirrored);
        assertErrorCount(1);
        assertTrue(errors().get(0).getMessage().contains("'on_register' must be an array"), errors().toString());
    }

    // --- D4: the aggregate branch follows declarations, not parsed entries ------------------------

    @Test
    void pureReloadModDoesNotRaiseTheAggregateError() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        IndexManifest manifest = parse("{\"on_reload\": [\"state_machines/machine.json\"]}");

        ResolvedIndex index = JsonTreeBuilder.resolveIndexManifests(MOD, List.of(manifest));

        assertTrue(index.declaredOnReload());
        assertFalse(index.declaredOnRegister());
        assertFalse(index.nothingDeclared(), "a pure reload mod did declare a domain");
        assertEquals(List.of("state_machines/machine.json"), index.onReloadPaths());
        assertEquals(List.of(), errors(), "a pure reload mod must not be reported as broken: " + errors());
    }

    @Test
    void allBrokenSchemaStillRaisesTheAggregateError() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        // The only field is present but broken: nothing is declared, so the aggregate branch must
        // still fire — a cross-repo schema break must never wash out into silence (D4 red line).
        ResolvedIndex index = JsonTreeBuilder.resolveIndexManifests(MOD, List.of(parse("{\"on_register\": \"x\"}")));

        assertTrue(index.nothingDeclared());
        assertEquals(List.of(), index.onRegisterPaths());
        List<String> messages = messages();
        assertTrue(messages.stream().anyMatch(m -> m.contains("'on_register' must be an array")),
                "the per-field failure is reported: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("in any index file")),
                "the aggregate error is reported too: " + messages);
    }

    // --- D5: `sources` is not an alias, it is an unknown field ------------------------------------

    @Test
    void legacySourcesFieldIsReportedAsUnknown() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        IndexManifest manifest = parse("{\"sources\": [\"a.json\"]}");

        assertFalse(manifest.declaredOnRegister());
        assertFalse(manifest.declaredOnReload());
        assertEquals(List.of(), manifest.onRegisterPaths(), "a legacy 'sources' array must not be consumed");
        List<String> messages = messages();
        assertTrue(messages.stream().anyMatch(m -> m.contains("unsupported field 'sources'")),
                "the legacy field takes the unknown-field path: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("'on_register' and 'on_reload'")),
                "the message must name the supported fields: " + messages);
    }

    // --- D6: a path in both domains is consumed by neither (fail-closed) --------------------------

    @Test
    void crossListedPathIsConsumedByNeitherDomain() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        IndexManifest manifest = parse("{\"on_register\": [\"shared.json\"], \"on_reload\": [\"shared.json\"]}");

        // The conflict is an aggregate-stage check: the manifest itself keeps both declarations.
        assertTrue(manifest.declaredOnRegister());
        assertTrue(manifest.declaredOnReload());
        assertEquals(List.of("shared.json"), manifest.onRegisterPaths());
        assertEquals(List.of("shared.json"), manifest.onReloadPaths());
        assertEquals(List.of(), errors(), "cross-listing is rejected at aggregation, not per file");

        ResolvedIndex index = JsonTreeBuilder.resolveIndexManifests(MOD, List.of(manifest));

        assertEquals(List.of(), index.onRegisterPaths(), "a cross-listed path must not be registered");
        assertEquals(List.of(), index.onReloadPaths(), "nor reloaded");
        assertErrorCount(1);
        String message = errors().get(0).getMessage();
        assertTrue(message.contains("listed in both 'on_register' and 'on_reload'"), message);
        assertTrue(message.contains("shared.json"), "the offending path must be named: " + message);

        // A cross-file conflict is treated the same way: the check spans every manifest of the mod.
        JsonTreeBuilder.clearLoadingErrors(MOD);
        IndexManifest registerManifest = new IndexManifest(true, false, List.of("shared.json"), List.of());
        IndexManifest reloadManifest = new IndexManifest(false, true, List.of(), List.of("shared.json"));
        ResolvedIndex crossFile = JsonTreeBuilder.resolveIndexManifests(
                MOD, List.of(registerManifest, reloadManifest));
        assertEquals(List.of(), crossFile.onRegisterPaths());
        assertEquals(List.of(), crossFile.onReloadPaths());
        assertErrorCount(1);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static IndexManifest parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return JsonTreeBuilder.parseIndexManifest(MOD, "index.json", root);
    }

    private static List<Throwable> errors() {
        return JsonTreeBuilder.getLoadingErrors(MOD);
    }

    private static List<String> messages() {
        return errors().stream().map(Throwable::getMessage).collect(Collectors.toList());
    }

    private static void assertErrorCount(int expected) {
        assertEquals(expected, errors().size(), "loading errors: " + errors());
    }
}
