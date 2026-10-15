package lib.kasuga.rendering.effect.post;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostProcessTargetDescriptorTest {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_pipeline_test", "scene_copy");

    @Test
    void resolvesScreenRelativeAndFixedSizes() {
        PostProcessTargetDescriptor half = PostProcessTargetDescriptor.builder(ID)
                .screenScale(0.5f)
                .build();
        assertEquals(960, half.resolveWidth(1920));
        assertEquals(540, half.resolveHeight(1080));

        PostProcessTargetDescriptor fixed = PostProcessTargetDescriptor.builder(ID)
                .fixedSize(320, 180)
                .build();
        assertEquals(320, fixed.resolveWidth(1920));
        assertEquals(180, fixed.resolveHeight(1080));
    }

    @Test
    void rejectsInvalidDimensionsAndClampsResolvedPixels() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessTargetDescriptor.builder(ID).screenScale(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessTargetDescriptor.builder(ID).fixedSize(-1, 10).build());

        PostProcessTargetDescriptor tiny = PostProcessTargetDescriptor.builder(ID)
                .screenScale(0.0001f)
                .build();
        assertEquals(1, tiny.resolveWidth(10));
        assertEquals(1, tiny.resolveHeight(10));
    }
}
