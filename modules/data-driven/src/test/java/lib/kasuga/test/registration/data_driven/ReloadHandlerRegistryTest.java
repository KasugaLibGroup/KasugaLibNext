package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.registration.data_driven.reload.Reloaded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The reload-domain extension registry, locked down as a plain JVM test: the symmetry with
 * {@code TypeHandlerRegistry} is the whole point of the refactor, so a handler can be registered and
 * found without the orchestrator knowing its type.
 *
 * <p>Also locks the two properties the registration-side sibling lacks: registration order is
 * preserved (it drives dispatch and last-wins), and {@link ReloadHandlerRegistry#clearForTest()}
 * actually resets the table so a fixture handler cannot leak into a later test — the missing hook on
 * {@code TypeHandlerRegistry} was recorded as a pitfall in the design.
 */
class ReloadHandlerRegistryTest {

    /** A minimal handler carrying only what the registry needs; it is never run. */
    private static final class FakeHandler implements ReloadHandler<String> {

        private final String typeName;

        private FakeHandler(String typeName) {
            this.typeName = typeName;
        }

        @Override
        public String typeName() {
            return typeName;
        }

        @Override
        public Set<String> globDirectories() {
            return Set.of();
        }

        @Override
        public void clearBucket() {}

        @Override
        public Decoded<String> decode(JsonObject root) {
            return Decoded.empty();
        }

        @Override
        public void register(String payload) {}

        @Override
        public void afterReload(List<Reloaded<String>> registered) {}
    }

    @AfterEach
    void resetRegistry() {
        ReloadHandlerRegistry.clearForTest();
    }

    @Test
    void registerThenGetReturnsTheSameHandler() {
        FakeHandler handler = new FakeHandler("fake_things");
        ReloadHandlerRegistry.register(handler);

        assertSame(handler, ReloadHandlerRegistry.get("fake_things"));
    }

    @Test
    void allPreservesRegistrationOrder() {
        ReloadHandlerRegistry.register(new FakeHandler("first"));
        ReloadHandlerRegistry.register(new FakeHandler("second"));
        ReloadHandlerRegistry.register(new FakeHandler("third"));

        List<String> order = new ArrayList<>();
        for (ReloadHandler<?> handler : ReloadHandlerRegistry.all()) {
            order.add(handler.typeName());
        }
        assertEquals(List.of("first", "second", "third"), order,
                "registration order drives dispatch and register order, so it must survive");
    }

    @Test
    void reRegisteringATypeNameReplacesInPlaceAndKeepsPosition() {
        ReloadHandlerRegistry.register(new FakeHandler("alpha"));
        ReloadHandlerRegistry.register(new FakeHandler("beta"));
        FakeHandler replacement = new FakeHandler("alpha");
        ReloadHandlerRegistry.register(replacement);

        List<String> order = new ArrayList<>();
        for (ReloadHandler<?> handler : ReloadHandlerRegistry.all()) {
            order.add(handler.typeName());
        }
        assertEquals(List.of("alpha", "beta"), order, "a replacement must not move or duplicate the key");
        assertSame(replacement, ReloadHandlerRegistry.get("alpha"));
    }

    @Test
    void unknownTypeNameIsNull() {
        assertNull(ReloadHandlerRegistry.get("never_registered"));
    }

    @Test
    void clearForTestEmptiesTheTable() {
        ReloadHandlerRegistry.register(new FakeHandler("fake_things"));
        ReloadHandlerRegistry.clearForTest();

        assertNull(ReloadHandlerRegistry.get("fake_things"));
        assertEquals(0, ReloadHandlerRegistry.all().size());
    }
}
