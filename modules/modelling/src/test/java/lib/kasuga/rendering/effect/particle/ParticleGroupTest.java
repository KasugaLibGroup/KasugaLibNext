package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleGroupTest {

    @Test
    void passiveInstanceDoesNotMoveWithoutControllerOrBehavior() {
        ParticleGroup group = new ParticleGroup();
        ParticleInstance instance = ParticleInstance.builder(
                new Transform().translate(1, 2, 3)
        ).build();
        group.add(instance);

        group.update(null);

        assertVector(new Vector3f(1, 2, 3), instance.transform().getPosition());
    }

    @Test
    void groupControllerUpdatesAllInstancesFromOneSnapshot() {
        ParticleGroup group = new ParticleGroup();
        ParticleInstance first = moving(0, 1);
        ParticleInstance second = moving(10, -2);
        group.add(first);
        group.add(second);
        group.controller((snapshot, updates, level) -> snapshot.instances().forEach(particle -> {
            Vector3f position = particle.position().add(particle.velocity());
            updates.submit(
                    particle.id(),
                    ParticleUpdate.keep(particle)
                            .withTransform(new Transform().translate(position))
            );
        }));

        group.update(null);

        assertVector(new Vector3f(1, 0, 0), first.transform().getPosition());
        assertVector(new Vector3f(8, 0, 0), second.transform().getPosition());
    }

    @Test
    void instanceBehaviorsReadStablePreUpdateGroupSnapshot() {
        ParticleGroup group = new ParticleGroup();
        ParticleBehavior swapPosition = (particle, snapshot, level) -> {
            ParticleSnapshot other = snapshot.instances().stream()
                    .filter(candidate -> candidate.id() != particle.id())
                    .findFirst()
                    .orElseThrow();
            return ParticleUpdate.keep(particle)
                    .withTransform(new Transform().translate(other.position()));
        };
        ParticleInstance first = ParticleInstance.builder(new Transform().translate(2, 0, 0))
                .behavior(swapPosition)
                .build();
        ParticleInstance second = ParticleInstance.builder(new Transform().translate(7, 0, 0))
                .behavior(swapPosition)
                .build();
        group.add(first);
        group.add(second);

        group.update(null);

        assertVector(new Vector3f(7, 0, 0), first.transform().getPosition());
        assertVector(new Vector3f(2, 0, 0), second.transform().getPosition());
    }

    @Test
    void ownedHandleRemovesOnlyItsExactInstance() {
        ParticleGroup group = new ParticleGroup();
        ParticleHandle handle = group.add(
                ParticleInstance.builder(new Transform()).build()
        );

        assertTrue(handle.isActive());
        assertEquals(1, group.size());
        assertTrue(handle.remove());
        assertFalse(handle.remove());
        assertFalse(handle.isActive());
        assertEquals(0, group.size());
    }

    @Test
    void neighborhoodQueryUsesDistanceAcrossSpatialCells() {
        ParticleGroup group = new ParticleGroup();
        ParticleHandle center = group.add(
                ParticleInstance.builder(new Transform()).build()
        );
        ParticleHandle nearby = group.add(
                ParticleInstance.builder(new Transform().translate(0.9f, 0, 0)).build()
        );
        group.add(ParticleInstance.builder(new Transform().translate(3, 0, 0)).build());

        ParticleGroupSnapshot snapshot = group.snapshot();

        assertEquals(
                java.util.Set.of(center.id(), nearby.id()),
                snapshot.near(new Vector3f(), 1.0f).stream()
                        .map(ParticleSnapshot::id)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    private static ParticleInstance moving(float x, float velocityX) {
        return ParticleInstance.builder(new Transform().translate(x, 0, 0))
                .velocity(new Vector3f(velocityX, 0, 0))
                .build();
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
    }
}
