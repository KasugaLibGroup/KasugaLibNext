package lib.kasuga.rendering.effect.builtin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillboardEffectTest {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("kasuga_pipeline_test", "textures/effect.png");

    @Test
    void interpolatesMotionAndExpiresAtItsLifetime() {
        BillboardEffect effect = BillboardEffect.builder(TEXTURE, new Vec3(1, 2, 3))
                .velocity(new Vec3(2, 1, -2))
                .lifetime(2)
                .motion(0.25, 0.5)
                .build();

        effect.tick(null);
        assertEquals(new Vec3(1, 2, 3), effect.position(0));
        assertEquals(new Vec3(2, 2.5, 2), effect.position(0.5f));
        assertTrue(effect.isAlive());

        effect.tick(null);
        assertEquals(new Vec3(4, 3.25, 0), effect.position(1));
        assertFalse(effect.isAlive());
    }

    @Test
    void interpolatesSizeColorAndRotation() {
        BillboardEffect effect = BillboardEffect.builder(TEXTURE, Vec3.ZERO)
                .lifetime(10)
                .size(1, 3)
                .color(
                        new BillboardEffect.Color(1, 0, 0, 1),
                        new BillboardEffect.Color(0, 0, 1, 0)
                )
                .rotation(0.25f, 0.5f)
                .build();

        assertEquals(2.0f, effect.size(5), 0.0001f);
        assertEquals(2.75f, effect.rotation(5), 0.0001f);
        BillboardEffect.Color color = effect.color(5);
        assertEquals(0.5f, color.red(), 0.0001f);
        assertEquals(0.5f, color.blue(), 0.0001f);
        assertEquals(0.5f, color.alpha(), 0.0001f);
    }

    @Test
    void validatesBuilderInputsAndClampsColors() {
        assertThrows(IllegalArgumentException.class,
                () -> BillboardEffect.builder(TEXTURE, Vec3.ZERO).lifetime(0));
        assertThrows(IllegalArgumentException.class,
                () -> BillboardEffect.builder(TEXTURE, Vec3.ZERO).size(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BillboardEffect.builder(TEXTURE, Vec3.ZERO).motion(0, -0.1));

        BillboardEffect.Color color = new BillboardEffect.Color(-1, 2, 0.5f, 3);
        assertEquals(0, color.red());
        assertEquals(1, color.green());
        assertEquals(0.5f, color.blue());
        assertEquals(1, color.alpha());
    }
}
