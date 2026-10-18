package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.handler.BlockEntityTypeHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1: {@link BlockEntityTypeHandler#extractEmbedded} 把块定义顶层的 {@code state_machine}
 * 字段经 params 通道注入到 block entity 的 JSON 中（与 model/model_name 同通道）。
 */
class BlockEntityStateMachineExtractTest {

    private final BlockEntityTypeHandler handler = new BlockEntityTypeHandler();

    private static JsonObject block(String id, String beType) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("type", "be_block");
        JsonObject be = new JsonObject();
        be.addProperty("type", beType);
        json.add("block_entity", be);
        return json;
    }

    @Test
    void stateMachineTopLevelInjectedIntoParams() {
        JsonObject block = block("test:panel", "fsm_be");
        block.addProperty("state_machine", "kasuga_lib:fsm_test_panel");

        List<JsonObject> embedded = handler.extractEmbedded(block);

        assertNotNull(embedded);
        assertEquals(1, embedded.size());
        JsonObject be = embedded.get(0);
        assertTrue(be.has("params"), "params should be created when missing");
        assertEquals("kasuga_lib:fsm_test_panel", be.getAsJsonObject("params").get("state_machine").getAsString());
        // 顶层字段语义保留：block_entity 本身的 type 未被覆盖
        assertEquals("fsm_be", be.get("type").getAsString());
    }

    @Test
    void existingParamsArePreserved() {
        JsonObject block = block("test:panel", "fsm_be");
        block.addProperty("state_machine", "kasuga_lib:fsm_test_panel");
        JsonObject params = new JsonObject();
        params.addProperty("model", "kasuga_lib:models/fsm/test_cube.obj");
        block.getAsJsonObject("block_entity").add("params", params);

        List<JsonObject> embedded = handler.extractEmbedded(block);

        JsonObject result = embedded.get(0).getAsJsonObject("params");
        assertEquals("kasuga_lib:models/fsm/test_cube.obj", result.get("model").getAsString());
        assertEquals("kasuga_lib:fsm_test_panel", result.get("state_machine").getAsString());
    }

    @Test
    void noStateMachineLeavesParamsUntouched() {
        JsonObject block = block("test:plain", "fsm_be");

        List<JsonObject> embedded = handler.extractEmbedded(block);

        JsonObject be = embedded.get(0);
        assertFalse(be.has("params"));
        assertEquals("test:plain", be.get("_parent_block").getAsString());
    }

    @Test
    void nonStringStateMachineIsIgnored() {
        JsonObject block = block("test:bad", "fsm_be");
        block.add("state_machine", new JsonObject());

        List<JsonObject> embedded = handler.extractEmbedded(block);

        JsonObject be = embedded.get(0);
        assertFalse(be.has("params"), "non-string state_machine must not be injected");
    }

    @Test
    void noBlockEntityReturnsNull() {
        JsonObject block = new JsonObject();
        block.addProperty("id", "test:bare");
        block.addProperty("type", "simple_block");
        assertNull(handler.extractEmbedded(block));
    }

    @Test
    void sourceJsonNotMutated() {
        JsonObject block = block("test:panel", "fsm_be");
        block.addProperty("state_machine", "kasuga_lib:fsm_test_panel");

        handler.extractEmbedded(block);

        assertFalse(block.getAsJsonObject("block_entity").has("params"),
            "extractEmbedded must deep-copy; the source block_entity stays untouched");
    }
}
