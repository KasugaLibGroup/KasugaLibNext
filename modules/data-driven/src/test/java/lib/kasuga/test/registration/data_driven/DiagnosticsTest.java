package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link Diagnostics} outlet: the mod dimension must stay the existing
 * {@link JsonTreeBuilder} bucket (the documented assertion contract), and the reserved
 * domain/source dimension must be writable and readable without ever mixing into it.
 */
class DiagnosticsTest {

    private static final String MOD_A = "diagnostics_mod_a";
    private static final String MOD_B = "diagnostics_mod_b";
    private static final String ASSET_KEY = "models/model_proxy.json";
    private static final String CONFIG_KEY = "kasuga-pbr.json";

    @AfterEach
    void clearBuckets() {
        Diagnostics.clearAll();
    }

    @Test
    void modDimensionIsTheExistingBucket() {
        IllegalStateException error = new IllegalStateException("boom");
        Diagnostics.report(MOD_A, error);

        assertEquals(List.of(error), Diagnostics.errors(MOD_A));
        assertEquals(List.of(error), Diagnostics.errorsByMod().get(MOD_A));
        assertEquals(List.of(error), JsonTreeBuilder.getLoadingErrors(MOD_A),
                "the mod dimension must delegate to the documented JsonTreeBuilder bucket");

        Diagnostics.clear(MOD_A);

        assertEquals(List.of(), Diagnostics.errors(MOD_A));
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(MOD_A), "clearing must reach the delegate");
    }

    @Test
    void sourceDimensionIsIsolatedFromTheModDimension() {
        IllegalStateException assetError = new IllegalStateException("asset");
        Diagnostics.report(Diagnostics.Domain.RELOAD_ASSETS, ASSET_KEY, assetError);

        assertEquals(List.of(assetError), Diagnostics.errors(Diagnostics.Domain.RELOAD_ASSETS, ASSET_KEY));
        assertEquals(List.of(assetError), Diagnostics.errors(Diagnostics.Domain.RELOAD_ASSETS),
                "the domain view aggregates its own sources");

        assertEquals(List.of(), Diagnostics.errors(MOD_A), "a source key must not be readable as a mod bucket");
        assertEquals(Map.of(), Diagnostics.errorsByMod(), "a source key must not enter the mod dimension");
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(), "nor the delegate the mod dimension wraps");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.CONFIG, ASSET_KEY),
                "another domain must not see the key");
        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_ASSETS, "other.json"),
                "another source of the same domain must not see the key");
    }

    @Test
    void clearingADomainLeavesModsAndOtherDomainsAlone() {
        Diagnostics.report(MOD_B, new IllegalStateException("mod"));
        Diagnostics.report(Diagnostics.Domain.RELOAD_ASSETS, ASSET_KEY, new IllegalStateException("asset"));
        Diagnostics.report(Diagnostics.Domain.CONFIG, CONFIG_KEY, new IllegalStateException("config"));

        Diagnostics.clear(Diagnostics.Domain.RELOAD_ASSETS);

        assertEquals(List.of(), Diagnostics.errors(Diagnostics.Domain.RELOAD_ASSETS));
        assertEquals(1, Diagnostics.errors(Diagnostics.Domain.CONFIG, CONFIG_KEY).size(),
                "a sibling domain survives a domain clear");
        assertEquals(1, Diagnostics.errors(MOD_B).size(), "a mod bucket survives a domain clear");

        Diagnostics.clearAll();

        assertTrue(Diagnostics.errors(Diagnostics.Domain.CONFIG).isEmpty());
        assertTrue(Diagnostics.errorsByMod().isEmpty());
    }

    @Test
    void summarizePrintsOneLinePerNonEmptyBucketDeterministically() {
        Diagnostics.report(MOD_B, new IllegalStateException("b"));
        Diagnostics.report(MOD_A, new IllegalStateException("a1"));
        Diagnostics.report(MOD_A, new IllegalStateException("a2"));
        Diagnostics.report(Diagnostics.Domain.RELOAD_ASSETS, ASSET_KEY, new IllegalStateException("asset"));
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, "data/none.json", new IllegalStateException("reload"));
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, "data/none.json", new IllegalStateException("reload2"));

        assertEquals(List.of(
                MOD_A + ": 2 error(s)",
                MOD_B + ": 1 error(s)",
                "RELOAD_DATA[data/none.json]: 2 error(s)",
                "RELOAD_ASSETS[" + ASSET_KEY + "]: 1 error(s)"
        ), Diagnostics.summarize());

        Diagnostics.clearAll();

        assertEquals(List.of(), Diagnostics.summarize(), "an empty outlet summarizes to nothing");
    }
}
