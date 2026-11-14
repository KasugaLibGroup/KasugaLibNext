package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRegistryTest {

    @Test
    void registerAndGet() {
        FactoryRegistry.BlockFactory factory = (id, params) -> null;
        FactoryRegistry.register("_test_dummy", factory);
        assertTrue(FactoryRegistry.contains("_test_dummy"));
        assertSame(factory, FactoryRegistry.get("_test_dummy"));
    }

    @Test
    void unknownTypeReturnsFalse() {
        assertFalse(FactoryRegistry.contains("_test_nonexistent_" + System.nanoTime()));
    }

    @Test
    void unknownTypeReturnsNull() {
        assertNull(FactoryRegistry.get("_test_nonexistent_" + System.nanoTime()));
    }

    @Test
    void registerDuplicateOverwrites() {
        FactoryRegistry.BlockFactory first = (id, params) -> null;
        FactoryRegistry.BlockFactory second = (id, params) -> null;
        FactoryRegistry.register("_test_overwrite", first);
        FactoryRegistry.register("_test_overwrite", second);
        assertSame(second, FactoryRegistry.get("_test_overwrite"));
    }

    @Test
    void tracksWhetherBlockFactoryCreatesMatchingItem() {
        FactoryRegistry.BlockFactory factory = (id, params) -> null;
        FactoryRegistry.register("_test_block_item_claim", factory, true);
        assertTrue(FactoryRegistry.createsBlockItem("_test_block_item_claim"));

        FactoryRegistry.register("_test_block_item_claim", factory, false);
        assertFalse(FactoryRegistry.createsBlockItem("_test_block_item_claim"));
    }

    @Test
    void factoryCreatesRegInstance() {
        // Requires NeoForge unitTest classpath for BlockReg
        FactoryRegistry.BlockFactory factory = FactoryRegistry.get("simple_block");
        assertNotNull(factory, "Expected 'simple_block' factory to be registered by DataDrivenTestFactories");
        Reg<?, Block> reg = factory.create("test_block_from_factory", null);
        assertNotNull(reg);
    }

    @Test
    void factoryWithParams() {
        // params_block factory reads "occluding" param to select between SimpleTestBlock and OccludingTestBlock
        FactoryRegistry.BlockFactory factory = FactoryRegistry.get("params_block");
        assertNotNull(factory, "Expected 'params_block' factory to be registered by DataDrivenTestFactories");

        com.google.gson.JsonObject occludingParam = new com.google.gson.JsonObject();
        occludingParam.addProperty("occluding", true);
        Reg<?, Block> occludingReg = factory.create("test_params_occluding", occludingParam);
        assertNotNull(occludingReg);

        com.google.gson.JsonObject simpleParam = new com.google.gson.JsonObject();
        simpleParam.addProperty("occluding", false);
        Reg<?, Block> simpleReg = factory.create("test_params_simple", simpleParam);
        assertNotNull(simpleReg);

        // null params should still work
        Reg<?, Block> nullParamsReg = factory.create("test_params_null", null);
        assertNotNull(nullParamsReg);
    }
}
