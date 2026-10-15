package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Definition bucketing: source semantics (script wins), per-id content hash, unified invalidation notifications. */
class FsmDefinitionsTest {

    private static Id rl(String path) {
        return Id.fromNamespaceAndPath("test", path);
    }

    private static StateMachineDefinition definition(String id) {
        return new StateMachineDefinition(rl(id), List.of(), List.of());
    }

    @Test
    void scriptDefinitionOverwritesResourceAndWins() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinition script = definition("def");
        definitions.registerResource(rl("def"), definition("resource"));
        definitions.register(rl("def"), script);
        assertSame(script, definitions.get(rl("def")));
        // a later resource write must not clobber the script definition
        definitions.registerResource(rl("def"), definition("resource2"));
        assertSame(script, definitions.get(rl("def")));
    }

    @Test
    void clearResourceKeepsScriptDefinitions() {
        FsmDefinitions definitions = new FsmDefinitions();
        List<Id> notified = new ArrayList<>();
        definitions.addListener(notified::add);
        definitions.registerResource(rl("r1"), definition("r1"));
        definitions.register(rl("s1"), definition("s1"));

        definitions.clearResource();

        assertNull(definitions.get(rl("r1")));
        assertNotNull(definitions.get(rl("s1")));
        assertEquals(List.of(rl("r1")), notified, "clearResource notifies per removed id");
    }

    @Test
    void removeNotifiesAndReportsPresence() {
        FsmDefinitions definitions = new FsmDefinitions();
        List<Id> notified = new ArrayList<>();
        definitions.addListener(notified::add);
        definitions.register(rl("a"), definition("a"));

        assertTrue(definitions.remove(rl("a")));
        assertNull(definitions.get(rl("a")));
        assertEquals(List.of(rl("a")), notified);
        assertFalse(definitions.remove(rl("a")), "removing an absent id is a no-op returning false");
        assertEquals(1, notified.size());
    }

    @Test
    void overwriteAndClearAllNotifyPerId() {
        FsmDefinitions definitions = new FsmDefinitions();
        List<Id> notified = new ArrayList<>();
        definitions.addListener(notified::add);
        definitions.register(rl("a"), definition("a"));

        // overwriting an existing entry notifies (a fresh registration does not)
        definitions.register(rl("a"), definition("a"));
        assertEquals(List.of(rl("a")), notified);

        definitions.register(rl("b"), definition("b"));
        definitions.clearAll();
        assertNull(definitions.get(rl("a")));
        assertNull(definitions.get(rl("b")));
        assertTrue(notified.containsAll(List.of(rl("a"), rl("b"))), "clearAll notifies once per dropped id");
    }

    @Test
    void hashIsPerIdAndStableAcrossUnrelatedReregister() {
        FsmDefinitions definitions = new FsmDefinitions();
        definitions.register(rl("a"), definition("a"));
        int hashA = definitions.hash(rl("a"));
        assertNotEquals(0, hashA, "a registered definition has a non-zero content hash");

        // re-registering the same content yields the same hash
        definitions.register(rl("a"), definition("a"));
        assertEquals(hashA, definitions.hash(rl("a")));

        // a different definition has a different hash
        definitions.register(rl("b"), definition("b"));
        assertNotEquals(hashA, definitions.hash(rl("b")));

        // re-registering an UNRELATED id does NOT change a's hash — the property the content-hash fixes
        definitions.register(rl("c"), definition("c"));
        assertEquals(hashA, definitions.hash(rl("a")),
                "unrelated re-register must not change another definition's hash");

        // absent id → 0
        assertEquals(0, definitions.hash(rl("missing")));
    }
}
