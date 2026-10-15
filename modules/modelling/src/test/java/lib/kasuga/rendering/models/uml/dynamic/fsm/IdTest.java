package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the contract of the pure {@link Id} type — the RL-replacement that must mirror
 * {@code net.minecraft.resources.ResourceLocation}'s parse/default-namespace/value-semantics/codec-wire-form
 * so the uml.fsm package can be MC-free without drifting the FSM id semantics or the sync definition-hash.
 */
class IdTest {

    @Test
    void parseNamespaceAndPath() {
        Id id = Id.parse("kasuga_lib:beacon");
        assertEquals("kasuga_lib", id.getNamespace());
        assertEquals("beacon", id.getPath());
    }

    @Test
    void barePathDefaultsToMinecraftNamespace() {
        Id id = Id.parse("foo");
        assertEquals("minecraft", id.getNamespace());
        assertEquals("foo", id.getPath());
    }

    @Test
    void tryParseRejectsInvalid() {
        assertNull(Id.tryParse(":path"), "empty namespace with ':' is invalid");
        assertNull(Id.tryParse("ns:UPPER"), "uppercase path invalid");
        assertNull(Id.tryParse("ns:sp ace"), "space invalid");
        assertNull(Id.tryParse("NS:path"), "uppercase namespace invalid");
        assertNull(Id.tryParse(""), "empty invalid");
        assertNull(Id.tryParse(null), "null invalid");
    }

    @Test
    void parseThrowsOnInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Id.parse("ns:UPPER"));
    }

    @Test
    void valueSemanticsAndOrdering() {
        assertEquals(Id.parse("a:b"), Id.parse("a:b"));
        assertEquals(Id.parse("a:b").hashCode(), Id.parse("a:b").hashCode());
        assertNotEquals(Id.parse("a:b"), Id.parse("a:c"));
        assertNotEquals(Id.parse("a:b"), Id.parse("b:b"));
        assertTrue(Id.parse("a:b").compareTo(Id.parse("a:c")) < 0, "path orders within namespace");
        assertTrue(Id.parse("a:z").compareTo(Id.parse("b:a")) < 0, "namespace orders first");
    }

    @Test
    void toStringRoundTripPreservesDefaultNamespace() {
        assertEquals("kasuga_lib:beacon", Id.parse("kasuga_lib:beacon").toString());
        assertEquals("minecraft:foo", Id.parse("foo").toString(), "bare path prints with the default namespace");
    }

    @Test
    void codecEncodesAsPlainJsonStringAndRoundTrips() {
        Id id = Id.parse("kasuga_lib:fsm_test_complex");
        JsonElement json = Id.CODEC.encodeStart(JsonOps.INSTANCE, id).result().orElseThrow();
        // A plain JSON string — byte-identical to how ResourceLocation.CODEC encodes it. This is load-bearing:
        // FsmDefinitions.hashOf hashes this exact form for sync identity.
        assertEquals("\"kasuga_lib:fsm_test_complex\"", json.toString());
        assertEquals(id, Id.CODEC.decode(JsonOps.INSTANCE, json).result().orElseThrow().getFirst());
    }
}
