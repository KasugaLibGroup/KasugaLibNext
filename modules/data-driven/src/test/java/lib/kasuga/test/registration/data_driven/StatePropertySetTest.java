package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.handler.BlockDef;
import lib.kasuga.registration.data_driven.handler.BlockTypeHandler;
import lib.kasuga.registration.data_driven.state.StatePropertyContext;
import lib.kasuga.registration.data_driven.state.StatePropertySet;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class StatePropertySetTest {
    private static final String MOD = "state_set_test";

    @AfterEach
    void clearErrors() {
        JsonTreeBuilder.clearLoadingErrors(MOD);
    }

    private static StatePropertySet parse(String json) {
        return StatePropertySet.parse(JsonParser.parseString(json));
    }

    private static StateDefinition<Block, BlockState> definition() {
        StateDefinition.Builder<Block, BlockState> builder = new StateDefinition.Builder<>(Blocks.STONE);
        StatePropertyContext.addTo(builder);
        return builder.create(Block::defaultBlockState, BlockState::new);
    }

    @Test
    void rejectsMalformedAndDangerouslyLargeDeclarations() {
        String[] invalid = {
                "[]",
                "{\"BadName\":{\"type\":\"boolean\"}}",
                "{\"open\":null}",
                "{\"open\":{\"type\":\"boolean\",\"default\":\"false\"}}",
                "{\"open\":{\"type\":\"boolean\",\"defaut\":false}}",
                "{\"x\":{\"type\":\"integer\",\"min\":-1,\"max\":2}}",
                "{\"x\":{\"type\":\"integer\",\"min\":0,\"max\":0}}",
                "{\"x\":{\"type\":\"integer\",\"min\":0.5,\"max\":2}}",
                "{\"x\":{\"type\":\"integer\",\"min\":0,\"max\":64}}",
                "{\"x\":{\"type\":\"integer\",\"min\":2147483646,\"max\":2147483647}}",
                "{\"x\":{\"type\":\"integer\",\"min\":0,\"max\":2,\"default\":3}}",
                "{\"x\":{\"type\":\"enum\",\"values\":[\"one\"]}}",
                "{\"x\":{\"type\":\"enum\",\"values\":[\"one\",\"one\"]}}",
                "{\"x\":{\"type\":\"enum\",\"values\":[\"one\",\"Bad\"]}}",
                "{\"x\":{\"type\":\"enum\",\"values\":[\"one\",\"two\"],\"default\":\"missing\"}}",
                "{\"open\":{\"type\":\"vanilla\",\"property\":\"NO_SUCH_PROPERTY\"}}",
                "{\"wrong\":{\"type\":\"vanilla\",\"property\":\"OPEN\"}}",
                "{\"open\":{\"type\":\"vanilla\",\"property\":\"OPEN\",\"default\":\"maybe\"}}"
        };
        for (String json : invalid) {
            assertThrows(IllegalArgumentException.class, () -> parse(json), json);
        }
        JsonObject excessive = new JsonObject();
        for (int i = 0; i < 13; i++) {
            excessive.add("p" + i, JsonParser.parseString("{\"type\":\"boolean\"}"));
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> StatePropertySet.parse(excessive)).getMessage().contains("combinations"));
    }

    @Test
    void constructionContextRestoresNestedAndExceptionalCalls() {
        StatePropertySet outer = parse("{\"outer\":{\"type\":\"boolean\",\"default\":true}}");
        StatePropertySet inner = parse("{\"inner\":{\"type\":\"integer\",\"min\":0,\"max\":1}}");
        StatePropertyContext.construct(outer, () -> {
            StateDefinition<Block, BlockState> first = definition();
            BooleanProperty firstProperty = (BooleanProperty) first.getProperty("outer");
            assertNotNull(firstProperty);
            assertTrue(StatePropertyContext.applyDefaults(first.any()).getValue(firstProperty));
            assertNull(first.getProperty("inner"));
            StatePropertyContext.construct(inner, () -> {
                StateDefinition<Block, BlockState> nested = definition();
                assertNotNull(nested.getProperty("inner"));
                assertNull(nested.getProperty("outer"));
                return nested;
            });
            assertThrows(IllegalStateException.class, () -> StatePropertyContext.construct(inner, () -> {
                throw new IllegalStateException("expected");
            }));
            StateDefinition<Block, BlockState> restored = definition();
            assertNotNull(restored.getProperty("outer"));
            assertNull(restored.getProperty("inner"));
            return restored;
        });
        assertTrue(definition().getProperties().isEmpty());
    }

    @Test
    void unsupportedLegacyFactoryIsReportedInsteadOfSilentlyIgnoringStates() {
        AtomicBoolean called = new AtomicBoolean();
        FactoryRegistry.register("_legacy_state_test", (id, params) -> {
            called.set(true);
            return null;
        });
        BlockTypeHandler handler = new BlockTypeHandler();
        BlockDef definition = handler.parse(JsonParser.parseString("""
                {"id":"example:test","type":"_legacy_state_test",
                 "state_properties":{"open":{"type":"boolean"}}}
                """).getAsJsonObject());
        RegBuildContext context = new RegBuildContext(MOD, new JsonRegistryGroup("root"));
        JsonTreeBuilder.applyUnchecked(handler, definition, context, MOD);
        assertFalse(called.get());
        assertNull(context.getBlockReg("example:test"));
        assertEquals(1, JsonTreeBuilder.getLoadingErrors(MOD).size());
        assertTrue(JsonTreeBuilder.getLoadingErrors(MOD).get(0).getMessage().contains("does not support"));
    }
}
