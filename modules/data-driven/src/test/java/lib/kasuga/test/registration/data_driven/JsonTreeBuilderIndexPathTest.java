package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the on-disk location of a mod's data-driven index directory, which is
 * {@code data/<mod_id>/kasuga_lib/data_driven/}.
 *
 * <p>{@link #indexDirectorySegmentsMatchCanonicalLayout()} pins the exact segment
 * array, so any regression to a differently-spelled directory (a hyphen instead of
 * an underscore, or failing to nest under the {@code kasuga_lib} parent) fails the
 * build instead of silently loading nothing at runtime.
 */
class JsonTreeBuilderIndexPathTest {

    private static final String MOD_ID = "kasuga_lib";

    @Test
    void indexDirectorySegmentsMatchCanonicalLayout() {
        assertArrayEquals(
                new String[]{"data", MOD_ID, "kasuga_lib", "data_driven"},
                JsonTreeBuilder.indexDirectorySegments(MOD_ID),
                "The index directory is data/<mod_id>/kasuga_lib/data_driven/");
    }

    @Test
    void indexDirectorySegmentsUseTheModIdVerbatim() {
        String[] segments = JsonTreeBuilder.indexDirectorySegments("kuayue");
        assertEquals(4, segments.length);
        assertEquals("kuayue", segments[1], "Second segment must be the mod id, verbatim");
    }

    @Test
    void indexDirectorySegmentsAreFreshPerCall() {
        assertNotSame(JsonTreeBuilder.indexDirectorySegments(MOD_ID),
                JsonTreeBuilder.indexDirectorySegments(MOD_ID),
                "Callers must not be able to mutate shared path segments");
    }

    /**
     * End-to-end check against the real mod index shipped by the contentTesting source set
     * ({@code data/kasuga_lib/kasuga_lib/data_driven/index.json}). Skips when NeoForge has not
     * loaded the mod under test (plain-JVM execution); under the ModDevGradle unit-test runtime
     * the mod is present and the assertion is binding.
     */
    @Test
    void contentTestingIndexLoadsWithoutErrors() {
        Assumptions.assumeTrue(neoForgeRuntimeLoaded(), "NeoForge runtime / mod list not available");
        Assumptions.assumeTrue(modLoaded(MOD_ID), "mod '" + MOD_ID + "' not loaded in this runtime");

        JsonTreeBuilder.clearLoadingErrors(MOD_ID);
        JsonRegistryGroup root = JsonTreeBuilder.buildForMod(MOD_ID);

        assertNotNull(root, "Expected the data-driven index at data/" + MOD_ID + "/kasuga_lib/data_driven/");
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(MOD_ID),
                "Loading the data-driven index must not report any errors");
    }

    private static boolean neoForgeRuntimeLoaded() {
        try {
            ModList modList = ModList.get();
            return modList != null && !modList.getModFiles().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean modLoaded(String modId) {
        for (IModInfo mod : ModList.get().getMods()) {
            if (modId.equals(mod.getModId())) return true;
        }
        return false;
    }
}
