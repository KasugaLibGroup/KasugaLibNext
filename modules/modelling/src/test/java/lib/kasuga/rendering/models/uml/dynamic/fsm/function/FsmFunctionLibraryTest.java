package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FsmFunctionLibrary#clearNamespace} must only drop the given namespace. */
class FsmFunctionLibraryTest {

    private static Id rl(String namespace, String path) {
        return Id.fromNamespaceAndPath(namespace, path);
    }

    @Test
    void clearNamespaceOnlyClearsThatNamespace() {
        FsmFunctionLibrary library = new FsmFunctionLibrary();
        library.registerCondition(rl("alpha", "a"), ctx -> true);
        library.registerAction(rl("alpha", "b"), ctx -> {});
        library.registerCondition(rl("beta", "c"), ctx -> true);
        library.registerAction(rl("beta", "d"), ctx -> {});

        library.clearNamespace("alpha");

        assertFalse(library.hasCondition(rl("alpha", "a")));
        assertFalse(library.hasAction(rl("alpha", "b")));
        assertNull(library.condition(rl("alpha", "a")));
        assertNull(library.action(rl("alpha", "b")));

        assertTrue(library.hasCondition(rl("beta", "c")));
        assertTrue(library.hasAction(rl("beta", "d")));
        assertNotNull(library.condition(rl("beta", "c")));
        assertNotNull(library.action(rl("beta", "d")));
    }

    @Test
    void clearNamespaceUnknownIsNoOp() {
        FsmFunctionLibrary library = new FsmFunctionLibrary();
        library.registerCondition(rl("alpha", "a"), ctx -> true);
        library.clearNamespace("unknown");
        library.clearNamespace(null);
        assertTrue(library.hasCondition(rl("alpha", "a")));
    }

    @Test
    void clearDropsEverything() {
        FsmFunctionLibrary library = new FsmFunctionLibrary();
        library.registerCondition(rl("alpha", "a"), ctx -> true);
        library.registerAction(rl("beta", "d"), ctx -> {});
        library.clear();
        assertFalse(library.hasCondition(rl("alpha", "a")));
        assertFalse(library.hasAction(rl("beta", "d")));
    }
}
