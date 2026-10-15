package lib.kasuga.rendering.effect.particle.instance;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParticleInstanceMeshTest {

    @Test
    void copiesBackendNeutralPositionMeshIntoBackendStorage() {
        ParticleInstanceMesh mesh = new ParticleInstanceMesh(
                ParticleInstanceMesh.Topology.TRIANGLES,
                0, 0, 0,
                1, 0, 0,
                0, 1, 0
        );
        FloatBuffer destination = FloatBuffer.allocate(9);

        mesh.writePositions(destination);

        assertEquals(3, mesh.vertexCount());
        assertEquals(9, destination.position());
        assertEquals(1, destination.get(3));
    }

    @Test
    void rejectsPartialVertices() {
        assertThrows(IllegalArgumentException.class, () -> new ParticleInstanceMesh(
                ParticleInstanceMesh.Topology.TRIANGLES, 0, 1
        ));
    }
}
