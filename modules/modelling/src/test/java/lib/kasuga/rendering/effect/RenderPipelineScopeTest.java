package lib.kasuga.rendering.effect;

import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPipelineScopeTest {

    @Test
    void closesOwnedRegistrationsInReverseOrderAndOnlyOnce() {
        RenderPipelineScope scope = RenderPipelineScope.create(owner());
        assertEquals(owner(), scope.owner());
        List<String> closed = new ArrayList<>();
        AutoCloseable first = () -> closed.add("first");
        AutoCloseable second = () -> closed.add("second");

        scope.own(first);
        scope.own(second);
        scope.own(first);
        assertEquals(2, scope.registrationCount());

        scope.close();
        scope.close();

        assertEquals(List.of("second", "first"), closed);
        assertTrue(scope.isClosed());
        assertEquals(0, scope.registrationCount());
    }

    @Test
    void parentOwnsChildRegistrarLifetime() {
        RenderPipelineScope parent = RenderPipelineScope.create(owner());
        RenderPipelineScope child = parent.child(
                ResourceLocation.fromNamespaceAndPath("kasuga_scope_test", "child")
        );

        assertEquals(1, parent.registrationCount());
        parent.close();
        assertTrue(child.isClosed());
    }

    @Test
    void immediatelyClosesRegistrationsAddedAfterScopeClosure() {
        RenderPipelineScope scope = RenderPipelineScope.create(owner());
        List<String> closed = new ArrayList<>();
        scope.close();

        assertThrows(IllegalStateException.class, () -> scope.own(() -> closed.add("late")));
        assertEquals(List.of("late"), closed);
    }

    private static ResourceLocation owner() {
        return ResourceLocation.fromNamespaceAndPath("kasuga_scope_test", "owner");
    }
}
