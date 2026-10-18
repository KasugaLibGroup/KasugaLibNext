package lib.kasuga.rendering.effect.builtin.blackhole;

import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackHoleEffectTest {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_black_hole_test", "primary");

    @Test
    void buildsDefaultsAndCreatesUpdatedCopy() {
        BlackHoleEffect original = BlackHoleEffect.builder(ID, new Vec3(1, 2, 3)).build();
        BlackHoleEffect moved = original.toBuilder()
                .position(new Vec3(4, 5, 6))
                .eventHorizonRadius(2.5f)
                .depthTest(false)
                .build();

        assertEquals(ID, original.id());
        assertTrue(original.depthTest());
        assertEquals(new Vec3(4, 5, 6), moved.position());
        assertEquals(2.5f, moved.eventHorizonRadius());
        assertEquals(1.0f, original.accretionDiskTilt());
        assertEquals(ID, moved.id());
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> BlackHoleEffect.builder(ID, Vec3.ZERO).eventHorizonRadius(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> BlackHoleEffect.builder(ID, Vec3.ZERO).influenceRadius(0.5f).build());
        assertThrows(IllegalArgumentException.class,
                () -> BlackHoleEffect.builder(ID, Vec3.ZERO).glowStrength(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> BlackHoleEffect.builder(ID, Vec3.ZERO).accretionDiskTilt(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> BlackHoleEffect.builder(ID, Vec3.ZERO).accretionDiskTilt(1.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> new BlackHoleEffect.Color(Float.NaN, 0, 0));
    }

    @Test
    void builtInShaderUsesTheImperativeProgramAsItsOnlySource() {
        var program = BlackHoleShaderProvider.program();
        var bundle = MinecraftGlsl150Backend.generate(program);

        assertEquals(BlackHoleShaderProvider.ID, program.id());
        assertTrue(bundle.fragmentSource().contains("uniform float HoleData[128];"));
        assertTrue(bundle.fragmentSource().contains("for (int hole = 0; hole < 8; ++hole)"));
        assertTrue(bundle.programJson().contains("\"name\": \"SceneSampler\""));
    }
}
