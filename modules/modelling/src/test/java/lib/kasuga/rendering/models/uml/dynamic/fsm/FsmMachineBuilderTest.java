package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2.1: {@link FsmMachineBuilder} —— registerDefinition → build → tick 版本递增 → activeStates
 * 往返；缺失定义返回 null；定义从共享定义桶（GLOBAL）解析。
 */
class FsmMachineBuilderTest {

    private static final Id DEF_ID = Id.fromNamespaceAndPath("test", "builder_flow");

    /** idle（1 tick）→ when_complete → active：1 次 tick 后必然产生状态变更。 */
    private static final String MAIN_JSON = """
            {
              "id": "test:builder_flow",
              "layers": [
                {
                  "id": "locomotion",
                  "mode": "base",
                  "initial_state": "idle",
                  "states": [
                    { "id": "idle", "duration_ticks": 1 },
                    { "id": "active", "duration_ticks": 5 }
                  ],
                  "transitions": [
                    { "id": "to_active", "from": "idle", "to": "active", "when_complete": true }
                  ]
                }
              ]
            }
            """;

    private static StateMachineDefinition decode() {
        JsonElement element = JsonParser.parseString(MAIN_JSON);
        DataResult<StateMachineDefinition> result = StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element);
        return result.resultOrPartial(error -> {
            throw new AssertionError("decode failed: " + error);
        }).orElseThrow();
    }

    @Test
    void buildTicksBumpsVersionAndReportsActiveStates() {
        StateMachineDefinition definition = decode();
        FsmRegistries.GLOBAL.definitions().register(DEF_ID, definition);

        StateMachine<Object> machine = FsmMachineBuilder.build(new Object(), definition, null);
        assertNotNull(machine);
        assertFalse(machine.isClientSide(), "no sink -> server-side machine");
        assertEquals("idle", machine.activeStates().get("locomotion"));

        int v0 = machine.version();
        for (int i = 0; i < 5 && "idle".equals(machine.activeStates().get("locomotion")); i++) {
            machine.tick();
        }
        assertTrue(machine.version() > v0, "when-complete transition must bump the version");
        Map<String, String> states = machine.activeStates();
        assertEquals("active", states.get("locomotion"), "activeStates must reflect the transition");
    }

    @Test
    void sinkMarksMachineClientSide() {
        StateMachineDefinition definition = decode();
        PoseSink recording = blender -> { };
        StateMachine<Object> machine = FsmMachineBuilder.build(new Object(), definition, recording);
        assertNotNull(machine);
        assertTrue(machine.isClientSide(), "non-null sink must mark the machine client-side");
    }

    @Test
    void missingDefinitionReturnsNull() {
        assertNull(FsmMachineBuilder.build(new Object(), null, null));
        assertNull(FsmMachineBuilder.findDefinition(Id.fromNamespaceAndPath("test", "definitely_missing")));
    }

    @Test
    void findDefinitionReadsSharedBucket() {
        StateMachineDefinition definition = decode();
        FsmRegistries.GLOBAL.definitions().register(DEF_ID, definition);
        assertEquals(definition, FsmMachineBuilder.findDefinition(DEF_ID));
        assertNull(FsmMachineBuilder.findDefinition(null));
    }
}
