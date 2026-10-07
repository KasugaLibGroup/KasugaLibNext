package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.handler.BlockEntityDef;
import lib.kasuga.registration.data_driven.handler.BlockEntityTypeHandler;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmBlockEntityFactories;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers data-driven's params-forwarding contract: {@link BlockEntityTypeHandler} lifts a block's
 * top-level {@code state_machine} into the embedded {@code block_entity.params} (next to the authored
 * {@code model}), and {@code apply} hands those params to the registered factory untouched.
 *
 * <p>The neighbouring tests stop at either end of that hop —
 * {@code BlockEntityStateMachineExtractTest} asserts extract in isolation and
 * {@code BlockEntityFactoryParamsTest} (in modelling) asserts the factory in isolation. This one drives the
 * whole parse → apply chain with a recording fake factory, fed the exact shape of the shipped
 * {@code kasuga_lib_content/fsm_blocks.json}.
 */
class FactoryParamsForwardingTest {

    private static final String SHIPPED_FSM_BLOCKS =
            "data/kasuga_lib/kasuga_lib_content/fsm_blocks.json";

    /** Captured by the recording fake factory registered below. */
    private static JsonObject capturedParams;
    private static String capturedId;
    private static boolean factoryReached;

    /**
     * The {@code fsm_be} factory in place before this test replaced it, or {@code null} when the real
     * built-in had not been loaded yet. {@link FactoryRegistry} is a bare last-wins map with no stack,
     * so this test must put the original back itself (see {@link #restoreFsmBeFactory()}).
     */
    private static FactoryRegistry.BlockEntityFactory originalFsmBeFactory;

    @BeforeAll
    static void registerRecordingFactory() {
        // The shipped entry declares "fsm_be"; capture what the apply chain hands over instead of
        // building a reg. modelling owns the real fsm_be factory (FsmBlockEntityFactories), so save it
        // here and restore it after the class — otherwise the fake would leak into every later test
        // class in the same JVM (and the registration chain would silently pass through the stand-in).
        originalFsmBeFactory = FactoryRegistry.getBlockEntityFactory("fsm_be");
        FactoryRegistry.registerBlockEntity("fsm_be", (id, validBlocks, params) -> {
            capturedId = id;
            capturedParams = params;
            factoryReached = true;
            return new FakeBlockEntityReg();
        });
    }

    @AfterAll
    static void restoreFsmBeFactory() {
        if (originalFsmBeFactory != null) {
            FactoryRegistry.registerBlockEntity("fsm_be", originalFsmBeFactory);
        } else {
            // FactoryRegistry has no removal API, so "remove what this test installed" is not
            // expressible; restoring the canonical built-in factory is the closest well-defined state
            // in a modelling JVM (it is also exactly what BlockEntityFactoryParamsTest installs).
            FsmBlockEntityFactories.registerBuiltin();
        }
    }

    @BeforeEach
    void clearCaptured() {
        capturedParams = null;
        capturedId = null;
        factoryReached = false;
    }

    @Test
    void shippedBlockForwardsStateMachineAndModelIntoFactoryParams() {
        JsonObject shipped = shippedFsmBlock();
        BlockEntityTypeHandler handler = new BlockEntityTypeHandler();

        List<JsonObject> embedded = handler.extractEmbedded(shipped);
        assertNotNull(embedded, "the shipped entry carries a block_entity");
        assertEquals(1, embedded.size());
        JsonObject be = embedded.get(0);

        // The block's top-level state_machine is forwarded through params — not left on the BE itself.
        assertFalse(be.has("state_machine"),
                "state_machine must travel in params, not remain a block-entity field");
        assertEquals("kasuga_lib:fsm_test_panel",
                be.getAsJsonObject("params").get("state_machine").getAsString(),
                "the top-level state_machine must land in the BE params the factory reads");
        // ...paired with the authored model that was already inside block_entity.params.
        assertEquals("kasuga_lib_test:models/fsm/test_cube.obj",
                be.getAsJsonObject("params").get("model").getAsString(),
                "the authored block_entity.params.model must survive the extract");
        // Deep copy: the source block_entity params must not gain the forwarded field.
        assertFalse(shipped.getAsJsonObject("block_entity").getAsJsonObject("params").has("state_machine"),
                "extractEmbedded must deep-copy; the source params stay untouched");

        BlockEntityDef def = handler.parse(be);
        assertEquals("fsm_be", def.beType(), "the declared block_entity type must be kept");
        assertEquals("kasuga_lib:fsm_test_block", def.parentBlockId(),
                "extractEmbedded must inject _parent_block from the host block id");

        handler.apply(def, buildContext(def.parentBlockId()));

        assertTrue(factoryReached, "apply must reach the block-entity factory");
        assertEquals("fsm_test_block_be", capturedId, "the BE id is derived from the parent block id");
        assertNotNull(capturedParams);
        assertEquals("kasuga_lib:fsm_test_panel", capturedParams.get("state_machine").getAsString(),
                "the forwarded state_machine must reach the factory verbatim");
        assertEquals("kasuga_lib_test:models/fsm/test_cube.obj", capturedParams.get("model").getAsString(),
                "the authored model must reach the factory verbatim");
    }

    @Test
    void factoryReceivesTheExtractedParamsObjectUnmodified() {
        JsonObject shipped = shippedFsmBlock();
        BlockEntityTypeHandler handler = new BlockEntityTypeHandler();

        JsonObject be = handler.extractEmbedded(shipped).get(0);
        BlockEntityDef def = handler.parse(be);

        handler.apply(def, buildContext(def.parentBlockId()));

        assertSame(be.getAsJsonObject("params"), capturedParams,
                "apply must hand over the extracted params object without rebuilding it");
        assertEquals(Set.of("model", "state_machine"), capturedParams.keySet(),
                "params carries the authored model plus the forwarded state_machine, and nothing else");
        assertFalse(capturedParams.has("_parent_block"),
                "_parent_block addresses the BE definition; it is not a factory param");
    }

    /** Root registry group plus a stand-in block reg so {@code apply} finds the parent block. */
    private static RegBuildContext buildContext(String parentBlockId) {
        RegBuildContext context =
                new RegBuildContext("kasuga_lib", new JsonRegistryGroup("kasuga_lib:json_root"));
        context.putReg("blocks", parentBlockId, new FakeBlockReg());
        return context;
    }

    /** Reads the shipped content file so the test locks the real distribution shape. */
    private static JsonObject shippedFsmBlock() {
        InputStream stream = FactoryParamsForwardingTest.class.getClassLoader()
                .getResourceAsStream(SHIPPED_FSM_BLOCKS);
        assertNotNull(stream, "expected the contentTesting resource on the test classpath: " + SHIPPED_FSM_BLOCKS);
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return root.getAsJsonArray("blocks").get(0).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("failed to read " + SHIPPED_FSM_BLOCKS, e);
        }
    }

    /** Minimal block reg: {@code apply} only needs a non-null parent to hang the BE reg off. */
    private static final class FakeBlockReg extends Reg<FakeBlockReg, Block> {
        @Override
        public Block getEntry() {
            return null;
        }
    }

    /** Minimal block-entity reg returned by the recording factory. */
    private static final class FakeBlockEntityReg extends Reg<FakeBlockEntityReg, Object> {
        @Override
        public Object getEntry() {
            return this;
        }
    }
}
