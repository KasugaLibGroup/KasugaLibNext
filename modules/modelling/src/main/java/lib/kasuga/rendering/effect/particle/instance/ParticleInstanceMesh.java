package lib.kasuga.rendering.effect.particle.instance;

import java.nio.FloatBuffer;
import java.util.Objects;

/**
 * Backend-neutral, non-indexed particle base mesh.
 *
 * <p>Only object-space xyz positions are stored here. Per-instance transform and color come from
 * {@code ParticleInstanceBuffer}.</p>
 */
public final class ParticleInstanceMesh {
    private final Topology topology;
    private final float[] positions;

    public ParticleInstanceMesh(Topology topology, float... positions) {
        this.topology = Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(positions, "positions");
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain one or more xyz vertices");
        }
        this.positions = positions.clone();
    }

    public Topology topology() {
        return topology;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public float positionComponent(int index) {
        return positions[index];
    }

    public int positionComponentCount() {
        return positions.length;
    }

    public void writePositions(FloatBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.remaining() < positions.length) {
            throw new IllegalArgumentException("destination does not have enough remaining floats");
        }
        destination.put(positions);
    }

    public enum Topology {
        LINES,
        TRIANGLES,
        TRIANGLE_STRIP
    }
}
