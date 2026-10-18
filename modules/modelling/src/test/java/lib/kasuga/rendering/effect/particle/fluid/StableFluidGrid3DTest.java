package lib.kasuga.rendering.effect.particle.fluid;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StableFluidGrid3DTest {

    @Test
    void pressureProjectionReducesAConcentratedVelocityDivergence() {
        StableFluidGrid3D grid = new StableFluidGrid3D(10);
        grid.addVelocity(0.5f, 0.5f, 0.5f, 2, -1, 0.7f);
        float before = grid.meanAbsoluteDivergence();

        grid.step(0.08f, new StableFluidGrid3D.Settings(0, 0, 1, 1, 16));

        float after = grid.meanAbsoluteDivergence();
        assertTrue(before > 0);
        assertTrue(Float.isFinite(after));
        assertTrue(after < before, "projection should reduce mean divergence");
    }

    @Test
    void densityDrivesBuoyancyAndRemainsFiniteAfterAdvection() {
        StableFluidGrid3D grid = new StableFluidGrid3D(12);
        grid.addDensity(0.5f, 0.25f, 0.5f, 4);
        grid.applyBuoyancy(0.1f, 0.8f);
        Vector3f velocity = grid.sampleVelocity(0.5f, 0.25f, 0.5f, new Vector3f());
        assertTrue(velocity.y > 0);

        grid.step(0.05f, new StableFluidGrid3D.Settings(
                0.0001f, 0.0001f, 0.99f, 0.995f, 8
        ));

        float density = grid.sampleDensity(0.5f, 0.25f, 0.5f);
        assertTrue(Float.isFinite(density));
        assertTrue(density >= 0);
    }

    @Test
    void gravityOnlyAcceleratesCellsContainingFluidDensity() {
        StableFluidGrid3D grid = new StableFluidGrid3D(8);
        grid.addDensity(0.5f, 0.5f, 0.5f, 1);
        grid.applyGravity(0.1f, -0.5f);

        assertTrue(grid.sampleVelocity(0.5f, 0.5f, 0.5f, new Vector3f()).y < 0);
        assertTrue(Math.abs(grid.sampleVelocity(0.1f, 0.1f, 0.1f, new Vector3f()).y) < 0.0001f);
    }

    @Test
    void solidEnvironmentCellsRejectDensityAndVelocity() {
        StableFluidGrid3D grid = new StableFluidGrid3D(12);
        grid.addDensity(0.5f, 0.5f, 0.5f, 4);
        grid.addVelocity(0.5f, 0.5f, 0.5f, 1, 2, 3);
        FluidEnvironment3D environment = FluidEnvironment3D.builder()
                .add(FluidConstraints3D.solidSphere(new Vector3f(0.5f), 0.2f))
                .build();

        grid.step(0.05f, settings(), environment);

        assertTrue(grid.isSolid(0.5f, 0.5f, 0.5f));
        assertTrue(grid.sampleDensity(0.5f, 0.5f, 0.5f) < 0.0001f);
        assertTrue(grid.sampleVelocity(0.5f, 0.5f, 0.5f, new Vector3f()).length() < 0.0001f);
    }

    @Test
    void sourceAndDirectionalForceCanDriveFlowWithoutPresetKnowledge() {
        StableFluidGrid3D grid = new StableFluidGrid3D(10);
        FluidEnvironment3D environment = FluidEnvironment3D.builder()
                .add(FluidConstraints3D.source(
                        new Vector3f(0.4f), new Vector3f(0.6f),
                        8, new Vector3f(0.2f, 0, 0)
                ))
                .add(FluidConstraints3D.directionalForce(
                        new Vector3f(0.4f), new Vector3f(0.6f),
                        new Vector3f(0, 1, 0)
                ))
                .build();

        grid.step(0.05f, settings(), environment);

        assertTrue(grid.sampleDensity(0.5f, 0.5f, 0.5f) > 0);
        Vector3f velocity = grid.sampleVelocity(0.5f, 0.5f, 0.5f, new Vector3f());
        assertTrue(velocity.x > 0);
        assertTrue(velocity.y > 0);
    }

    private static StableFluidGrid3D.Settings settings() {
        return new StableFluidGrid3D.Settings(0, 0, 1, 1, 8);
    }
}
