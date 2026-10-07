package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.context.BuildContext;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link JsonTreeBuilder} loading errors are bucketed per mod: one mod's
 * {@code buildForMod} (which clears its own bucket) must never erase another mod's diagnostics.
 *
 * <p>The record/query/clear round trip and the isolation guarantee are asserted in plain JVM; the
 * one test that must exercise the real {@code ModList}-backed {@code buildForMod} path is guarded
 * with {@link Assumptions#assumeTrue}.
 */
class JsonTreeBuilderLoadingErrorTest {

    private static final String MOD_A = "loading_error_mod_a";
    private static final String MOD_B = "loading_error_mod_b";

    @AfterEach
    void clearBuckets() {
        JsonTreeBuilder.clearLoadingErrors();
    }

    @Test
    void errorsAreIsolatedPerMod() {
        JsonTreeBuilder.clearLoadingErrors();

        IllegalStateException errorA = new IllegalStateException("boom-a");
        JsonTreeBuilder.addLoadingError(MOD_A, errorA);

        // Clearing another mod's bucket must not touch mod A's diagnostics.
        JsonTreeBuilder.clearLoadingErrors(MOD_B);

        assertEquals(List.of(errorA), JsonTreeBuilder.getLoadingErrors(MOD_A),
                "mod A's error must survive a clear of mod B");
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(MOD_B));
    }

    @Test
    void recordQueryClearRoundTrip() {
        JsonTreeBuilder.clearLoadingErrors();
        IOException error = new IOException("bad source path");
        JsonTreeBuilder.addLoadingError(MOD_A, error);

        assertEquals(List.of(error), JsonTreeBuilder.getLoadingErrors(MOD_A), "per-mod query");
        assertEquals(List.of(error), JsonTreeBuilder.getLoadingErrorsByMod().get(MOD_A), "bulk query");
        assertEquals(List.of(error), JsonTreeBuilder.getLoadingErrors(), "aggregate query");

        JsonTreeBuilder.clearLoadingErrors(MOD_A);

        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(MOD_A), "cleared per-mod");
        assertTrue(JsonTreeBuilder.getLoadingErrorsByMod().getOrDefault(MOD_A, List.of()).isEmpty());
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(), "aggregate empty after clear");
    }

    @Test
    void aggregateOrdersModsById() {
        JsonTreeBuilder.clearLoadingErrors();
        Throwable errorA = new IllegalStateException("a");
        Throwable errorB = new IllegalStateException("b");
        JsonTreeBuilder.addLoadingError(MOD_B, errorB);
        JsonTreeBuilder.addLoadingError(MOD_A, errorA);

        assertEquals(List.of(errorA, errorB), JsonTreeBuilder.getLoadingErrors(),
                "aggregate is ordered by mod id so results are deterministic");
    }

    @Test
    void unknownModHasNoErrors() {
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors("never_recorded_mod"));
    }

    @Test
    void clearAllEmptiesEveryBucket() {
        JsonTreeBuilder.addLoadingError(MOD_A, new IllegalStateException("a"));
        JsonTreeBuilder.addLoadingError(MOD_B, new IllegalStateException("b"));

        JsonTreeBuilder.clearLoadingErrors();

        assertTrue(JsonTreeBuilder.getLoadingErrorsByMod().isEmpty());
    }

    /**
     * Exercises the production clear path: {@code buildForMod} for a mod that does not exist in the
     * runtime must only clear that mod's bucket, leaving a sentinel error recorded for another mod.
     * Requires the NeoForge {@code ModList} (plain-JVM runs skip it).
     */
    @Test
    void buildForModOfAnotherModDoesNotClearRecordedErrors() {
        Assumptions.assumeTrue(neoForgeRuntimeLoaded(), "NeoForge runtime / mod list not available");

        JsonTreeBuilder.clearLoadingErrors();
        IllegalStateException sentinel = new IllegalStateException("sentinel");
        JsonTreeBuilder.addLoadingError(MOD_A, sentinel);

        JsonTreeBuilder.buildForMod("kasuga_lib_no_such_mod");

        assertEquals(List.of(sentinel), JsonTreeBuilder.getLoadingErrors(MOD_A),
                "buildForMod for another mod must only clear its own bucket");
    }

    private static boolean neoForgeRuntimeLoaded() {
        try {
            ModList modList = ModList.get();
            return modList != null && !modList.getModFiles().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // --- the three production diagnostics that are not reachable through a well-formed mod jar ---

    /**
     * The index-directory scan is the first thing {@code buildForMod} does; a directory it cannot
     * list used to leave only a log line. It must now be bucketed, with the path and the mod in the
     * message so the failure is locatable.
     */
    @Test
    void unlistableIndexDirectoryIsRecordedInTheBucket(@TempDir Path tempDir) throws IOException {
        JsonTreeBuilder.clearLoadingErrors();
        Path notADirectory = Files.writeString(tempDir.resolve("not-a-dir.json"), "{}");

        List<Path> manifests = JsonTreeBuilder.listIndexFiles(notADirectory, MOD_A);

        assertEquals(List.of(), manifests, "a directory that cannot be listed yields no manifest");
        List<Throwable> errors = JsonTreeBuilder.getLoadingErrors(MOD_A);
        assertEquals(1, errors.size(), "the scan failure must land in the mod's bucket: " + errors);
        String message = errors.get(0).getMessage();
        assertTrue(message.contains(MOD_A), "the bucket entry must name the mod: " + message);
        assertTrue(message.contains(notADirectory.toString()), "the bucket entry must name the path: " + message);
    }

    /** The happy path of the same scan: {@code .json} manifests only, sorted by path. */
    @Test
    void indexDirectoryScanReturnsSortedJsonManifestsOnly(@TempDir Path tempDir) throws IOException {
        JsonTreeBuilder.clearLoadingErrors();
        Files.writeString(tempDir.resolve("b_manifest.json"), "{}");
        Files.writeString(tempDir.resolve("a_manifest.json"), "{}");
        Files.writeString(tempDir.resolve("notes.txt"), "ignored");

        List<Path> manifests = JsonTreeBuilder.listIndexFiles(tempDir, MOD_A);

        assertEquals(List.of(tempDir.resolve("a_manifest.json"), tempDir.resolve("b_manifest.json")), manifests);
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(MOD_A), "a listable directory reports nothing");
    }

    /**
     * A handler whose {@code apply} throws must leave a diagnostic: the registration did not happen,
     * and silently dropping that is exactly the failure mode the loader guarantees against.
     */
    @Test
    void failingHandlerApplyIsRecordedInTheBucket() {
        JsonTreeBuilder.clearLoadingErrors();

        JsonTreeBuilder.applyUnchecked(new FailingHandler(), new JsonObject(), null, MOD_A);

        List<Throwable> errors = JsonTreeBuilder.getLoadingErrors(MOD_A);
        assertEquals(1, errors.size(), "the apply failure must land in the mod's bucket: " + errors);
        Throwable error = errors.get(0);
        assertTrue(error.getMessage().contains("failing_test_type"), "must name the type: " + error.getMessage());
        assertTrue(error.getMessage().contains(MOD_A), "must name the mod: " + error.getMessage());
        assertTrue(error.getMessage().contains("boom-apply"), "must carry the failure summary: " + error.getMessage());
        assertNotNull(error.getCause(), "the original exception must be kept as the cause");
        assertEquals("boom-apply", error.getCause().getMessage());
    }

    /** A registered type that always fails; {@code apply} ignores both definition and context. */
    private static final class FailingHandler implements TypeHandler<JsonObject> {
        @Override
        public String getTypeName() {
            return "failing_test_type";
        }

        @Override
        public int getPhase() {
            return PHASE_CONTENT;
        }

        @Override
        public JsonObject parse(JsonObject json) {
            return json;
        }

        @Override
        public void apply(JsonObject definition, BuildContext context) {
            throw new IllegalStateException("boom-apply");
        }
    }
}
