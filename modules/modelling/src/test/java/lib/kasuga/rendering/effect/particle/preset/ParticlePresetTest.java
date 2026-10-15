package lib.kasuga.rendering.effect.particle.preset;

import lib.kasuga.rendering.effect.particle.ParticleGroup;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.fluid.StableFluidGrid3D;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticlePresetTest {

    @Test
    void smokeIsGroupControlledAndExpiresWithoutAnInstanceTickMethod() {
        SmokePlumePreset preset = new SmokePlumePreset(
                new SmokePlumePreset.Settings(3, 0.1f, 1, 0, 0, 0, 1, 1)
        );
        ParticleInstance smoke = preset.create(
                new Vector3f(), new Vector3f(), 1, 0, new Vector4f(1, 1, 1, 0.5f)
        );
        ParticleGroup group = new ParticleGroup();
        group.controller(preset.controller());
        group.add(smoke);

        group.update(null);
        assertEquals(0.1f, smoke.transform().getPosition().y, 0.0001f);
        assertEquals(1, group.size());

        group.update(null);
        group.update(null);
        assertEquals(0, group.size());
    }

    @Test
    void rainRecyclesAtTheTopOfItsGroupVolume() {
        RainFieldPreset preset = new RainFieldPreset(new RainFieldPreset.Settings(
                new Vector3f(1, 1, 1), 5, new Vector3f(), 0.1f, 0.5f, 0.5f
        ));
        preset.center(new Vector3f(4, 8, 12));
        ParticleInstance rain = preset.create(new Random(7), new Vector4f(1));
        ParticleGroup group = new ParticleGroup();
        group.controller(preset.controller());
        group.add(rain);

        group.update(null);

        assertEquals(9.0f, rain.transform().getPosition().y, 0.0001f);
    }

    @Test
    void boidsUseOptionalPerInstanceBehaviorAndRespectSpeedLimit() {
        BoidsPreset preset = new BoidsPreset(new BoidsPreset.Settings(
                5, 0.1f, 0.1f, 0.1f, 0.2f, 0.02f, 10, 0.1f
        ));
        ParticleInstance first = preset.create(
                new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0), 1, new Vector4f(1)
        );
        ParticleInstance second = preset.create(
                new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), 1, new Vector4f(1)
        );
        ParticleGroup group = new ParticleGroup();
        group.add(first);
        group.add(second);

        group.update(null);

        assertTrue(first.velocity().length() <= 0.2001f);
        assertTrue(second.velocity().length() <= 0.2001f);
    }

    @Test
    void gasSmokeUsesSharedDensityVelocityFieldToMoveTracers() {
        GasSmokePreset preset = new GasSmokePreset(new GasSmokePreset.Settings(
                6, new Vector3f(2), 20, 0.05f, 0.5f, 1, 1,
                new StableFluidGrid3D.Settings(0, 0, 1, 1, 4)
        ));
        preset.inject(new Vector3f(), 5, new Vector3f(0, 0.2f, 0));
        ParticleInstance tracer = preset.createTracer(
                new Vector3f(), 1, new Vector4f(1, 1, 1, 0.8f)
        );
        ParticleGroup group = new ParticleGroup();
        group.bufferController(preset.bufferController());
        group.add(tracer);

        group.update(null);

        assertTrue(tracer.transform().getPosition().y > 0);
        assertTrue(tracer.color().w > 0);
    }

    @Test
    void liquidPresetAdvectsTracerThroughProjectedLowViscosityField() {
        LiquidFlowPreset preset = new LiquidFlowPreset(new LiquidFlowPreset.Settings(
                6, new Vector3f(2), 0.05f, -0.1f, 1,
                new StableFluidGrid3D.Settings(0, 0.00001f, 1, 1, 4)
        ));
        preset.inject(new Vector3f(), 4, new Vector3f(0.25f, 0, 0));
        ParticleInstance tracer = preset.createTracer(
                new Vector3f(), 0.2f, new Vector4f(0, 0.4f, 1, 0.8f)
        );
        ParticleGroup group = new ParticleGroup();
        group.bufferController(preset.bufferController());
        group.add(tracer);

        group.update(null);

        Vector3f position = tracer.transform().getPosition();
        assertTrue(Float.isFinite(position.x));
        assertTrue(Float.isFinite(position.y));
        assertTrue(Float.isFinite(position.z));
        assertTrue(group.size() == 1);
    }

    @Test
    void liquidTracerKeepsFlowingAfterLeavingTheSolverVolume() {
        LiquidFlowPreset preset = new LiquidFlowPreset(new LiquidFlowPreset.Settings(
                6, new Vector3f(1), 0.1f, -0.2f, 1,
                new StableFluidGrid3D.Settings(0, 0, 1, 1, 4)
        ));
        ParticleInstance tracer = preset.createTracer(
                new Vector3f(2, 0, 0), 0.2f, new Vector4f(0, 0.4f, 1, 0.8f)
        );
        tracer.velocity(new Vector3f(0.4f, 0, 0));
        ParticleGroup group = new ParticleGroup();
        AtomicBoolean worldCollisionApplied = new AtomicBoolean();
        preset.tracerCollision((previousPosition, position, velocity, radius) -> {
            worldCollisionApplied.set(true);
            position.y = previousPosition.y;
            velocity.y = 0;
        });
        group.bufferController(preset.bufferController());
        group.add(tracer);

        group.update(null);

        assertTrue(group.size() == 1);
        assertTrue(worldCollisionApplied.get());
        assertTrue(tracer.transform().getPosition().x > 2);
        assertEquals(0, tracer.transform().getPosition().y, 0.0001f);
    }
}
