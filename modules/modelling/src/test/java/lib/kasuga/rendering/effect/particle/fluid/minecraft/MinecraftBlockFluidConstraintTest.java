package lib.kasuga.rendering.effect.particle.fluid.minecraft;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftBlockFluidConstraintTest {

    @Test
    void validatesWorldVolumeAndRefreshCadence() {
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftBlockFluidConstraint(new Vector3f(1, 0, 1), 4));
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftBlockFluidConstraint(new Vector3f(1), 0));
    }

    @Test
    void sweptAxisMovementStopsAtCollisionShapeFaces() {
        assertEquals(0.1, MinecraftBlockFluidConstraint.clipMovement(
                0.7, 0.9, 1.0, 2.0, 0.4
        ), 0.000001);
        assertEquals(-0.1, MinecraftBlockFluidConstraint.clipMovement(
                1.1, 1.3, 0.0, 1.0, -0.4
        ), 0.000001);
        assertEquals(0.05, MinecraftBlockFluidConstraint.clipMovement(
                0.7, 0.9, 1.0, 2.0, 0.05
        ), 0.000001);
    }
}
