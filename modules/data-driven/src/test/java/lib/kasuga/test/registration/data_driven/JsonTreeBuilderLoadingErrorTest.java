package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
