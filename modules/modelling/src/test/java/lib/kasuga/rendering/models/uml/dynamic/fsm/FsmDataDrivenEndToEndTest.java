package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncClient;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.FsmSyncKey;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncPayload;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.FsmSyncState;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 数据驱动 FSM 全链路端到端样例（纯 JVM，无 MC 运行时依赖）：
 *
 * <pre>
 * JSON 定义 (CODEC 解码) → DefinitionStateMachineFactory 建机 → tick 推进
 *   → snapshot() 快照 → FsmSyncPayload 线格式（FriendlyByteBuf roundtrip 模拟网络）
 *   → FsmSyncClient.apply（definitionHash 校验 + conform）→ 客户端机状态与服务端一致
 * </pre>
 *
 * 覆盖：codec 解码、factory 构建（含 layersById）、版本递增、过渡进度同步、线格式往返、
 * 客户端应用与过期守卫 —— 即 A+B+C 三集群交付的完整数据面闭环。
 */
class FsmDataDrivenEndToEndTest {

    private static final String DEFINITION_JSON = """
            {
              "id": "test:panel",
              "layers": [
                {
                  "id": "base", "mode": "base", "weight": 1.0, "bone_mask": "*",
                  "initial_state": "idle",
                  "states": [
                    { "id": "idle", "duration_ticks": 2,
                      "pose": { "bones": [ { "name": "cube",
                        "transform": { "translate": [0, 0, 0] }, "mode": "replace" } ] } },
                    { "id": "active", "duration_ticks": 5,
                      "pose": { "bones": [ { "name": "cube",
                        "transform": { "translate": [0, 1, 0] }, "mode": "replace" } ] } }
                  ],
                  "transitions": [
                    { "id": "idle_to_active", "from": "idle", "to": "active",
                      "when_complete": true, "cross_fade_seconds": 0.2 }
                  ]
                }
              ]
            }
            """;

    private static final int DEFINITION_HASH = 7;
    private static final FsmSyncKey KEY = new FsmSyncKey(
            Id.parse("test:panel"),
            "minecraft:overworld",
            42L);

    private static StateMachine<Object> machine() {
        StateMachineDefinition definition = StateMachineDefinition.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString(DEFINITION_JSON))
                .resultOrPartial(error -> {
                    throw new IllegalStateException("definition decode failed: " + error);
                })
                .orElseThrow().getFirst();
        assertNotNull(definition);
        return new DefinitionStateMachineFactory<Object>(new FsmFunctionLibrary())
                .build(new Object(), definition, null);
    }

    /** Mirrors {@code FsmSyncServer.toPayload}: machine → wire payload (indices + elapsed). */
    private static FsmSyncPayload toPayload(StateMachine<?> machine, boolean force) {
        List<FsmSyncPayload.LayerEntry> layers = new ArrayList<>();
        int layerIndex = 0;
        for (Layer<?> layer : machine.layers()) {
            State<?> active = layer.active();
            Transition<?> transition = layer.activeTransition();
            layers.add(new FsmSyncPayload.LayerEntry(
                    layerIndex,
                    active == null ? -1 : layer.activeStateIndex(),
                    layer.stateElapsedTicks(),
                    transition == null ? -1 : layer.activeTransitionIndex(),
                    layer.transitionElapsed()
            ));
            layerIndex++;
        }
        return new FsmSyncPayload(
                KEY.machineId(), KEY.dimension(), KEY.ownerDiscriminator(),
                machine.version(), DEFINITION_HASH, force, layers, List.of());
    }

    @Test
    void serverToClientSyncReproducesState() {
        StateMachine<Object> server = machine();
        assertNotNull(server.layerOrNull("base"), "layersById must be populated by the factory");

        server.tick(); // elapsed 1
        server.tick(); // elapsed 2 → idle completes, crossfade idle→active in flight
        server.tick(); // transitionElapsed = 0.05

        // snapshot → wire format (roundtrip simulates the network hop)
        FsmSyncPayload payload = toPayload(server, false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        FsmSyncPayload.CODEC.encode(buf, payload);
        FsmSyncPayload decoded = FsmSyncPayload.CODEC.decode(buf);
        assertEquals(payload, decoded);

        // fresh client machine + sync client
        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> DEFINITION_HASH);
        clientSync.bind(KEY, client);
        clientSync.apply(decoded);

        // client reproduces server state: active state, elapsed, in-flight transition
        assertEquals(server.activeStates(), client.activeStates());
        assertEquals(server.layer("base").stateElapsedTicks(), client.layer("base").stateElapsedTicks());
        assertEquals(server.layer("base").activeTransition().id(),
                client.layer("base").activeTransition().id());
        assertEquals(server.layer("base").transitionElapsed(),
                client.layer("base").transitionElapsed(), 1e-4f);

        // stale replay is dropped (dedup by server version), heartbeat force replays
        clientSync.apply(decoded); // same version → dropped
        assertEquals(server.activeStates(), client.activeStates());
        clientSync.apply(toPayload(server, true)); // force → conformed again (idempotent)
        assertEquals(server.activeStates(), client.activeStates());
    }

    @Test
    void definitionHashMismatchDropsThePacket() {
        StateMachine<Object> server = machine();
        server.tick();
        FsmSyncPayload payload = toPayload(server, false);

        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> DEFINITION_HASH + 1);
        clientSync.bind(KEY, client);
        int before = client.version();
        clientSync.apply(payload);
        assertEquals(before, client.version(), "mismatched definition version must be dropped");
    }
}
