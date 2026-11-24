package lib.kasuga.rendering.models.mc.api.item;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemModelRenderersTest {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("test", "models/block/test.bbmodel");

    @Test
    void defaultsGuiScaleToOne() {
        assertEquals(1.0f, new ItemModelRenderers.Binding(MODEL, null).guiScale());
    }

    @Test
    void keepsExplicitGuiScale() {
        assertEquals(0.3f, new ItemModelRenderers.Binding(MODEL, null, 0.3f).guiScale());
    }

    @Test
    void rejectsInvalidGuiScale() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemModelRenderers.Binding(MODEL, null, 0.0f)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemModelRenderers.Binding(MODEL, null, Float.POSITIVE_INFINITY)
        );
    }
}
