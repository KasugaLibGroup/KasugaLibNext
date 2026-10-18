package lib.kasuga.rendering.models.mc.api.pbr;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PbrConversionRegistryTest {

    @Test
    void appliesRulesByPriorityAndSupportsUnregistration() {
        PbrMaterialContext context = context("explicit-user-label");
        PbrConversionSettings defaults = new PbrConversionSettings(0.2f, 10, 0, 0.08f, 0);

        try (PbrConversionRegistry.Registration ignoredA = PbrConversionRegistry.register(
                ResourceLocation.parse("kasuga_lib:test_first"), 10,
                (material, current) -> current.withSmoothness(0.7f)
        ); PbrConversionRegistry.Registration ignoredB = PbrConversionRegistry.register(
                ResourceLocation.parse("kasuga_lib:test_second"), 20,
                (material, current) -> current.withEmission(current.smoothness())
        )) {
            PbrConversionSettings result = PbrConversionRegistry.apply(context, defaults);
            assertEquals(0.7f, result.smoothness());
            assertEquals(0.7f, result.emission());
        }

        assertEquals(defaults, PbrConversionRegistry.apply(context, defaults));
    }

    @Test
    void userConfigGlobSupportsResourceAndUnicodeMaterialPatterns() {
        assertTrue(PbrUserConfig.globMatches("kasuga_lib:models/pmx/*", "kasuga_lib:models/pmx/test.mmd.zip"));
        assertTrue(PbrUserConfig.globMatches("*丝袜*", "黑色丝袜材质"));
        assertTrue(PbrUserConfig.globMatches("material_??", "MATERIAL_07"));
        assertFalse(PbrUserConfig.globMatches("kasuga_lib:other/*", "kasuga_lib:models/pmx/test.mmd.zip"));
    }

    private static PbrMaterialContext context(String name) {
        return new PbrMaterialContext(
                ResourceLocation.parse("kasuga_lib:model"), ResourceLocation.parse("kasuga_lib:texture"),
                0, name, name, "", 1, 1, 1, 1,
                0.04f, 0.04f, 0.04f, 0.2f, 0.2f, 0.2f,
                32, false, true, 256, 256
        );
    }
}
