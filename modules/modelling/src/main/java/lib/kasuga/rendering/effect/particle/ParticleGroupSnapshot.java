package lib.kasuga.rendering.effect.particle;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3f;

/** Stable pre-update view shared by every controller and behavior in one group step. */
public final class ParticleGroupSnapshot {
    private final List<ParticleSnapshot> instances;
    private final Map<Long, ParticleSnapshot> byId;
    private final Map<Integer, SpatialIndex> spatialIndexes = new ConcurrentHashMap<>();

    ParticleGroupSnapshot(List<ParticleSnapshot> instances) {
        this.instances = List.copyOf(instances);
        Map<Long, ParticleSnapshot> index = new LinkedHashMap<>();
        for (ParticleSnapshot instance : this.instances) {
            index.put(instance.id(), instance);
        }
        byId = Map.copyOf(index);
    }

    public List<ParticleSnapshot> instances() {
        return instances;
    }

    public Optional<ParticleSnapshot> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public ParticleSnapshot require(long id) {
        return Objects.requireNonNull(byId.get(id), "Unknown particle instance: " + id);
    }

    public int size() {
        return instances.size();
    }

    public boolean isEmpty() {
        return instances.isEmpty();
    }

    /**
     * Returns instances within a spherical neighborhood. A cell grid matching the requested radius
     * is built lazily once per group snapshot and reused by every behavior in that update.
     */
    public List<ParticleSnapshot> near(Vector3f position, float radius) {
        Objects.requireNonNull(position, "position");
        if (!Float.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("radius must be finite and positive");
        }
        SpatialIndex index = spatialIndexes.computeIfAbsent(
                Float.floatToIntBits(radius), ignored -> new SpatialIndex(radius, instances)
        );
        return index.near(position, radius);
    }

    private static final class SpatialIndex {
        private final float cellSize;
        private final Map<Cell, List<ParticleSnapshot>> cells = new HashMap<>();

        private SpatialIndex(float cellSize, List<ParticleSnapshot> instances) {
            this.cellSize = cellSize;
            for (ParticleSnapshot instance : instances) {
                cells.computeIfAbsent(cell(instance.position()), ignored -> new ArrayList<>())
                        .add(instance);
            }
        }

        private List<ParticleSnapshot> near(Vector3f position, float radius) {
            Cell center = cell(position);
            float radiusSquared = radius * radius;
            List<ParticleSnapshot> result = new ArrayList<>();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        List<ParticleSnapshot> candidates = cells.get(
                                new Cell(center.x + x, center.y + y, center.z + z)
                        );
                        if (candidates == null) continue;
                        for (ParticleSnapshot candidate : candidates) {
                            if (candidate.position().distanceSquared(position) <= radiusSquared) {
                                result.add(candidate);
                            }
                        }
                    }
                }
            }
            return List.copyOf(result);
        }

        private Cell cell(Vector3f position) {
            return new Cell(
                    (int) Math.floor(position.x / cellSize),
                    (int) Math.floor(position.y / cellSize),
                    (int) Math.floor(position.z / cellSize)
            );
        }
    }

    private record Cell(int x, int y, int z) {
    }
}
