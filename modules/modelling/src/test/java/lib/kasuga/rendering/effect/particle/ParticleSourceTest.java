package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleSourceTest {

    @Test
    void fractionalEmissionRateAccumulatesAtThePlacedSource() {
        ParticleGroup group = new ParticleGroup();
        ParticleSource source = new ParticleSource(group, ParticleSource.Settings.builder()
                .position(new Vector3f(4, 5, 6))
                .emissionRate(0.25f)
                .size(new Vector3f(2, 3, 4))
                .operator(ParticleOperators.identity())
                .build());

        for (int tick = 0; tick < 3; tick++) source.update(null);
        assertEquals(0, group.size());
        source.update(null);

        assertEquals(1, group.size());
        ParticleSnapshot particle = group.snapshot().instances().getFirst();
        assertVector(new Vector3f(4, 5, 6), particle.position());
        assertVector(
                new Vector3f(2, 3, 4),
                particle.transform().transform().getScale(new Vector3f())
        );
    }

    @Test
    void gravityAndDefaultIntegratorUpdatePhysicalParticle() {
        ParticleGroup group = new ParticleGroup();
        ParticleSource source = new ParticleSource(group, ParticleSource.Settings.builder()
                .emissionRate(0)
                .initialVelocity(new Vector3f(1, 1, 0))
                .affectedByGravity(true)
                .gravity(new Vector3f(0, -0.25f, 0))
                .build());
        ParticleInstance particle = source.burst(1).getFirst().instance();

        group.update(null);

        assertVector(new Vector3f(1, 0.75f, 0), particle.velocity());
        assertVector(new Vector3f(1, 0.75f, 0), particle.transform().getPosition());
    }

    @Test
    void oneOperatorCanMoveGrowRotateAndFadeCubeSmoke() {
        ParticleGroup group = new ParticleGroup();
        ParticleOperator smoke = ParticleOperators
                .physics(new Vector3f(0, 0.02f, 0), 0.98f)
                .then(ParticleOperators.scale(1.1f))
                .then(ParticleOperators.rotate(new Vector3f(0, 0.1f, 0)))
                .then(ParticleOperators.fade(0.8f));
        ParticleSource source = new ParticleSource(group, ParticleSource.Settings.builder()
                .emissionRate(0)
                .initialVelocity(new Vector3f(0, 0.1f, 0))
                .size(2)
                .color(new Vector4f(0.7f, 0.7f, 0.7f, 0.5f))
                .lifetimeTicks(20)
                .operator(smoke)
                .build());
        ParticleInstance particle = source.burst(1).getFirst().instance();

        group.update(null);

        assertTrue(particle.transform().getPosition().y > 0.1f);
        assertVector(
                new Vector3f(2.2f),
                particle.transform().transform().getScale(new Vector3f())
        );
        assertEquals(0.4f, particle.color().w, 0.0001f);
        assertTrue(Math.abs(particle.transform().getRotation().y) > 0);
    }

    @Test
    void customParticleTypeAndTransformLambdaAreSupported() {
        ParticleGroup group = new ParticleGroup();
        AtomicInteger creations = new AtomicInteger();
        ParticleSource source = new ParticleSource(group, ParticleSource.Settings.builder()
                .emissionRate(0)
                .particleType(spawn -> {
                    creations.incrementAndGet();
                    return ParticleInstance.builder(
                                    spawn.transform().translateWorld(new Vector3f(1, 0, 0))
                            )
                            .velocity(spawn.velocity())
                            .color(spawn.color())
                            .attributes(42)
                            .build();
                })
                .operator(ParticleOperators.transform(
                        transform -> transform.translateWorld(new Vector3f(0, 2, 0))
                ))
                .build());
        ParticleInstance particle = source.burst(1).getFirst().instance();

        group.update(null);

        assertEquals(1, creations.get());
        assertEquals(42, particle.attributes()[0], 0.0001f);
        assertEquals(1, particle.transform().getPosition().x, 0.0001f);
        assertEquals(2, particle.transform().getPosition().y, 0.0001f);
    }

    @Test
    void sourceParametersCanBeChangedForLaterParticles() {
        ParticleGroup group = new ParticleGroup();
        ParticleSource source = new ParticleSource(group, ParticleSource.Settings.builder()
                .emissionRate(0)
                .size(1)
                .color(new Vector4f(1, 0, 0, 1))
                .operator(ParticleOperators.identity())
                .build());
        ParticleInstance first = source.burst(1).getFirst().instance();

        source.position(new Vector3f(8, 0, 0))
                .size(3)
                .color(new Vector4f(0, 0, 1, 0.5f));
        ParticleInstance second = source.burst(1).getFirst().instance();

        assertEquals(0, first.transform().getPosition().x, 0.0001f);
        assertEquals(1, first.transform().transform().getScale(new Vector3f()).x, 0.0001f);
        assertEquals(8, second.transform().getPosition().x, 0.0001f);
        assertEquals(3, second.transform().transform().getScale(new Vector3f()).x, 0.0001f);
        assertEquals(0.5f, second.color().w, 0.0001f);
    }

    @Test
    void emittedParticlesAreRemovedAtTheirConfiguredLifetime() {
        ParticleGroup group = new ParticleGroup();
        ParticleSource source = new ParticleSource(group, ParticleSource.Settings.builder()
                .emissionRate(0)
                .lifetimeTicks(3)
                .operator(ParticleOperators.identity())
                .build());
        source.burst(1);

        group.update(null);
        group.update(null);
        assertEquals(1, group.size());
        group.update(null);
        assertEquals(0, group.size());
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
    }
}
