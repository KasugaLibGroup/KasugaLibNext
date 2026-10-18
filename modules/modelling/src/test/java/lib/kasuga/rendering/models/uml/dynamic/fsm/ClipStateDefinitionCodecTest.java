package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StateDefinition.CODEC}: the optional {@code clip} field accepts both a plain id string and an object form. */
class ClipStateDefinitionCodecTest {

    private static StateDefinition decode(String json) {
        DataResult<StateDefinition> result = StateDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        return result.resultOrPartial(error -> {
            throw new AssertionError("decode failed: " + error);
        }).orElseThrow();
    }

    @Test
    void plainStringClipDecodesWithLoopFalse() {
        StateDefinition def = decode("{ \"id\": \"s1\", \"clip\": \"kasuga_lib:fan_on\" }");
        assertTrue(def.clip().isPresent(), "clip field must decode from a plain id string");
        assertEquals(Id.parse("kasuga_lib:fan_on"), def.clip().get().id());
        assertFalse(def.clip().get().loop(), "plain-string clip defaults to non-loop");
    }

    @Test
    void objectClipDecodesWithLoopFlag() {
        StateDefinition def = decode("{ \"id\": \"s1\", \"clip\": { \"id\": \"kasuga_lib:fan_on\", \"loop\": true } }");
        assertTrue(def.clip().isPresent(), "clip field must decode from the object form");
        assertEquals(Id.parse("kasuga_lib:fan_on"), def.clip().get().id());
        assertTrue(def.clip().get().loop());
    }

    @Test
    void absentClipStaysEmpty() {
        StateDefinition def = decode("{ \"id\": \"s1\" }");
        assertTrue(def.clip().isEmpty(), "a state without a clip field has no clip");
    }

    @Test
    void encodeRoundTripsBothShapes() {
        // object form (loop=true) encodes as an object; plain form (loop=false) encodes as a string
        StateDefinition objectForm = decode("{ \"id\": \"s1\", \"clip\": { \"id\": \"kasuga_lib:fan_on\", \"loop\": true } }");
        StateDefinition plainForm = decode("{ \"id\": \"s1\", \"clip\": \"kasuga_lib:fan_on\" }");
        assertTrue(roundTrip(objectForm).clip().get().loop());
        assertFalse(roundTrip(plainForm).clip().get().loop());
    }

    private static StateDefinition roundTrip(StateDefinition def) {
        JsonElement encoded = StateDefinition.CODEC.encodeStart(JsonOps.INSTANCE, def)
                .resultOrPartial(error -> {
                    throw new AssertionError("encode failed: " + error);
                }).orElseThrow();
        return StateDefinition.CODEC.parse(JsonOps.INSTANCE, encoded)
                .resultOrPartial(error -> {
                    throw new AssertionError("round-trip decode failed: " + error);
                }).orElseThrow();
    }
}