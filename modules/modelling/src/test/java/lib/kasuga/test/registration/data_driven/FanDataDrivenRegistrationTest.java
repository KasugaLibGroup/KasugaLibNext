package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registration half of the data-driven fan fixture: on the real (FML) test runtime the mod's own
 * index manifest — {@code data/kasuga_lib/kasuga_lib/data_driven/modelling.json}, shipped by the
 * modelling contentTesting source set — is consumed by {@code JsonTreeBuilder.buildForMod} and the
 * {@code kasuga_lib:test_fan_formula_data_driven} block (with its embedded block entity) really lands
 * in {@code BuiltInRegistries}.
 *
 * <p>The two NeoForge-dependent assertions are guarded with {@link Assumptions#assumeTrue}: the plain
 * JVM {@code modelUnitTest} gate has no ModList and skips them, while the FML {@code test} gate binds
 * them. That is the intended split — the registration path can only be judged with a mod list present.
 */
class FanDataDrivenRegistrationTest {

    private static final String MOD_ID = "kasuga_lib";

    private static final ResourceLocation BLOCK_ID =
            ResourceLocation.parse("kasuga_lib:test_fan_formula_data_driven");

    private static final ResourceLocation ORIGINAL_BLOCK_ID =
            ResourceLocation.parse("kasuga_lib:test_fan_formula");

    private static final ResourceLocation BE_ID =
            ResourceLocation.parse("kasuga_lib:test_fan_formula_data_driven_be");

    @Test
    void dataDrivenFanBlockLandsInTheRegistryWithoutDisturbingTheOriginal() {
        Assumptions.assumeTrue(neoForgeRuntimeLoaded(), "NeoForge runtime / mod list not available");

        Block block = BuiltInRegistries.BLOCK.get(BLOCK_ID);
        assertNotSame(Blocks.AIR, block,
                "the on_register content file must have registered " + BLOCK_ID);
        assertTrue(block instanceof EntityBlock, "the data-driven fan block must host a block entity");
        assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(BE_ID),
                "the embedded block entity must be registered as " + BE_ID);

        // The programmatic fixture shares the machine vars/vars and model RL but keeps its own id; the
        // data-driven registration must not displace it.
        assertNotSame(Blocks.AIR, BuiltInRegistries.BLOCK.get(ORIGINAL_BLOCK_ID),
                "the programmatic " + ORIGINAL_BLOCK_ID + " must still be registered");
    }

    /** Re-running the real index reader must be clean: no source, schema or factory error. */
    @Test
    void dataDrivenIndexLoadsWithoutErrors() {
        Assumptions.assumeTrue(neoForgeRuntimeLoaded(), "NeoForge runtime / mod list not available");

        JsonTreeBuilder.clearLoadingErrors(MOD_ID);
        JsonRegistryGroup root = JsonTreeBuilder.buildForMod(MOD_ID);

        assertNotNull(root, "the modelling index must produce a registry group");
        assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(MOD_ID),
                "the on_register content file must load without any recorded error");
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
