package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncClient;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncPayload;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncServer;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sync projection: {@code sync}-declared parameters travel server→client in the
 * {@link FsmSyncPayload} vars section — collection on the server ({@link FsmSyncServer#toPayload}
 * via a test-exposing subclass), incremental dedup against {@link FsmSyncDedup#lastVars}, forced
 * heartbeat carry, and landing on the client ({@link FsmSyncClient#apply}).
 */
class FsmSyncVarSyncTest {

    private static final ParameterSpec<Float> DOOR_OPEN = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("test", "door_open"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .sync(true)
            .build();

    private static final ParameterSpec<Float> LOCAL_SPEED = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("test", "local_speed"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .build(); // sync=false → never carried

    private static FsmSyncKey key() {
        return new FsmSyncKey(Id.fromNamespaceAndPath("test", "demo"), "test:overworld", 1L);
    }

    private static StateMachine<Object> machineWith(ParameterSpec<?>... specs) {
        return StateMachine.builder(new Object())
                .declaredVars(Set.of(specs))
                .build();
    }

    /** Exposes the package-private wire builder so the server payload can be captured without a {@code ServerPlayer}. */
    private static final class ExposedSyncServer extends FsmSyncServer {
        ExposedSyncServer(Function<Id, Integer> hash) {
            super(hash);
        }

        FsmSyncPayload build(FsmSyncKey key, StateMachine<?> machine, int version, boolean force) {
            return toPayload(key, machine, version, force);
        }
    }

    @Test
    void serverCarriesChangedSyncVarsOnly() {
        StateMachine<Object> machine = machineWith(DOOR_OPEN, LOCAL_SPEED);
        machine.setInternal(DOOR_OPEN, 0.75f);
        machine.setInternal(LOCAL_SPEED, 5f);

        ExposedSyncServer server = new ExposedSyncServer(id -> 9);
        FsmSyncPayload payload = server.build(key(), machine, machine.version(), false);

        assertEquals(1, payload.vars().size(), "only sync-declared vars are carried");
        FsmSyncPayload.VarEntry entry = payload.vars().get(0);
        assertEquals("test:door_open", entry.varId());
        assertEquals("float", entry.type());
        assertEquals(0.75f, entry.value());
    }

    @Test
    void unchangedVarsAreNotReCarriedUntilForce() {
        StateMachine<Object> machine = machineWith(DOOR_OPEN);
        machine.setInternal(DOOR_OPEN, 0.5f);

        ExposedSyncServer server = new ExposedSyncServer(id -> 9);
        FsmSyncKey key = key();

        FsmSyncPayload first = server.build(key, machine, machine.version(), false);
        assertEquals(1, first.vars().size(), "first push carries the value");

        FsmSyncPayload second = server.build(key, machine, machine.version(), false);
        assertTrue(second.vars().isEmpty(), "unchanged value is not re-carried (incremental)");

        machine.setInternal(DOOR_OPEN, 0.9f);
        FsmSyncPayload third = server.build(key, machine, machine.version(), false);
        assertEquals(1, third.vars().size(), "changed value is carried again");
        assertEquals(0.9f, third.vars().get(0).value());

        // a forced heartbeat replays the full vars section even when unchanged
        FsmSyncPayload forced = server.build(key, machine, machine.version(), true);
        assertEquals(1, forced.vars().size());
        assertEquals(0.9f, forced.vars().get(0).value());
    }

    @Test
    void nonSyncVarsAreNeverCarried() {
        StateMachine<Object> machine = machineWith(LOCAL_SPEED);
        machine.setInternal(LOCAL_SPEED, 5f);

        ExposedSyncServer server = new ExposedSyncServer(id -> 9);
        FsmSyncPayload payload = server.build(key(), machine, machine.version(), false);

        assertTrue(payload.vars().isEmpty());
    }

    @Test
    void varsWithUnknownRuntimeTypeAreSkipped() {
        // Double has no built-in StateVarType — collectVars logs and skips instead of failing
        ParameterSpec<Double> spec = ParameterSpec.<Double>parameter(
                Id.fromNamespaceAndPath("test", "weird"), Double.class, Codec.DOUBLE)
                .defaultValue(0d)
                .sync(true)
                .build();
        StateMachine<Object> machine = machineWith(spec);
        machine.setInternal(spec, 1.5d);

        ExposedSyncServer server = new ExposedSyncServer(id -> 9);
        FsmSyncPayload payload = server.build(key(), machine, machine.version(), false);

        assertTrue(payload.vars().isEmpty());
    }

    @Test
    void clientLandsSyncedVarsFromWirePayload() {
        // server side: build a payload carrying the changed var
        StateMachine<Object> serverMachine = machineWith(DOOR_OPEN);
        serverMachine.setInternal(DOOR_OPEN, 0.75f);
        ExposedSyncServer server = new ExposedSyncServer(id -> 9);
        FsmSyncPayload serverPayload = server.build(key(), serverMachine, serverMachine.version(), false);

        // wire hop
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        FsmSyncPayload.CODEC.encode(buf, serverPayload);
        FsmSyncPayload decoded = FsmSyncPayload.CODEC.decode(buf);

        // client side: bind a locally-built machine with the same declaration and apply
        StateMachine<Object> client = machineWith(DOOR_OPEN);
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        clientSync.bind(key(), client);
        clientSync.apply(decoded);

        assertEquals(0.75f, client.get(DOOR_OPEN), "synced value lands in the client machine's var store");
    }

    @Test
    void clientSkipsUnknownVarIds() {
        StateMachine<Object> client = machineWith(DOOR_OPEN);
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        clientSync.bind(key(), client);

        FsmSyncPayload payload = new FsmSyncPayload(
                Id.fromNamespaceAndPath("test", "demo"), "test:overworld", 1L, 1, 9, false,
                List.of(),
                List.of(new FsmSyncPayload.VarEntry("test:unknown", "float", 0.5f))
        );
        clientSync.apply(payload); // must not throw

        assertEquals(0f, client.get(DOOR_OPEN), "unknown var is skipped, declared var untouched");
    }

    @Test
    void clientSkipsTypeMismatchedVars() {
        StateMachine<Object> client = machineWith(DOOR_OPEN);
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        clientSync.bind(key(), client);

        FsmSyncPayload payload = new FsmSyncPayload(
                Id.fromNamespaceAndPath("test", "demo"), "test:overworld", 1L, 1, 9, false,
                List.of(),
                List.of(new FsmSyncPayload.VarEntry("test:door_open", "int", 5))
        );
        clientSync.apply(payload);

        assertEquals(0f, client.get(DOOR_OPEN), "type-mismatched var is skipped, declared var untouched");
    }
}