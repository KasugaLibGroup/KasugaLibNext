package lib.kasuga.rendering.models.mc.dynamic.collision;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/** Spatial index and chunk snapshots shared by server collision and client movement prediction. */
final class ModelCollisionIndex {
    static final int MAX_BOXES_PER_CHUNK = 16_384;
    private static final int MAX_CHUNKS_PER_BOX = 1_024;
    private static final Object REMOTE = new Object();

    private final Map<Object, Map<Long, List<AABB>>> owners = new HashMap<>();
    private final Map<Long, Map<Object, List<AABB>>> chunks = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    synchronized void replace(Object owner, List<AABB> boxes) {
        Map<Long, List<AABB>> previous = owners.getOrDefault(owner, Map.of());
        Map<Long, List<AABB>> next = partition(boxes);
        if (previous.equals(next)) return;
        for (Map.Entry<Long, List<AABB>> entry : next.entrySet()) {
            Map<Object, List<AABB>> bucket = chunks.getOrDefault(entry.getKey(), Map.of());
            int total = entry.getValue().size();
            for (Map.Entry<Object, List<AABB>> other : bucket.entrySet()) {
                if (!other.getKey().equals(owner)) total += other.getValue().size();
            }
            if (total > MAX_BOXES_PER_CHUNK) throw new IllegalArgumentException("Too many model collision boxes in one chunk");
        }

        for (long key : previous.keySet()) {
            if (!next.containsKey(key)) {
                put(key, owner, List.of());
                dirty.add(key);
            }
        }
        for (Map.Entry<Long, List<AABB>> entry : next.entrySet()) {
            long key = entry.getKey();
            if (entry.getValue().equals(previous.get(key))) continue;
            put(key, owner, entry.getValue());
            dirty.add(key);
        }
        if (next.isEmpty()) owners.remove(owner);
        else owners.put(owner, next);
    }

    private void put(long key, Object owner, List<AABB> boxes) {
        if (boxes.isEmpty()) {
            Map<Object, List<AABB>> bucket = chunks.get(key);
            if (bucket == null) return;
            bucket.remove(owner);
            if (bucket.isEmpty()) chunks.remove(key);
        } else {
            chunks.computeIfAbsent(key, ignored -> new HashMap<>()).put(owner, boxes);
        }
    }

    synchronized void receive(long key, List<AABB> boxes) {
        put(key, REMOTE, List.copyOf(boxes));
    }

    synchronized void dropUnloadedSnapshots(LongPredicate isLoaded) {
        chunks.keySet().removeIf(key -> !isLoaded.test(key));
    }

    synchronized List<AABB> snapshot(long key) {
        Map<Object, List<AABB>> bucket = chunks.get(key);
        if (bucket == null) return List.of();
        List<AABB> result = new ArrayList<>();
        for (List<AABB> boxes : bucket.values()) result.addAll(boxes);
        return List.copyOf(result);
    }

    synchronized Set<Long> drainDirtyChunks() {
        Set<Long> keys = Set.copyOf(dirty);
        dirty.clear();
        return keys;
    }

    synchronized List<VoxelShape> collisions(AABB query, Object excludedOwner, List<VoxelShape> vanilla) {
        if (chunks.isEmpty()) return vanilla;
        AABB area = query.inflate(1.0e-7);
        ChunkRange range = new ChunkRange(area);
        Set<AABB> matches = new HashSet<>();
        if (range.size() > chunks.size()) {
            for (Map.Entry<Long, Map<Object, List<AABB>>> entry : chunks.entrySet()) {
                if (range.contains(entry.getKey())) collect(entry.getValue(), excludedOwner, area, matches);
            }
        } else {
            for (int x = range.minX; x <= range.maxX; x++) {
                for (int z = range.minZ; z <= range.maxZ; z++) {
                    Map<Object, List<AABB>> bucket = chunks.get(ChunkPos.asLong(x, z));
                    if (bucket != null) collect(bucket, excludedOwner, area, matches);
                }
            }
        }
        if (matches.isEmpty()) return vanilla;
        List<VoxelShape> result = new ArrayList<>(vanilla.size() + matches.size());
        result.addAll(vanilla);
        for (AABB box : matches) result.add(Shapes.create(box));
        return result;
    }

    private static void collect(Map<Object, List<AABB>> bucket, Object excludedOwner, AABB query, Set<AABB> matches) {
        for (Map.Entry<Object, List<AABB>> entry : bucket.entrySet()) {
            if (entry.getKey() == excludedOwner) continue;
            for (AABB box : entry.getValue()) {
                if (box.intersects(query)) matches.add(box);
            }
        }
    }

    private static Map<Long, List<AABB>> partition(List<AABB> boxes) {
        Map<Long, List<AABB>> result = new HashMap<>();
        for (AABB box : boxes) {
            ChunkRange range = new ChunkRange(box);
            if (range.size() > MAX_CHUNKS_PER_BOX) throw new IllegalArgumentException("A model collision box spans too many chunks");
            for (int x = range.minX; x <= range.maxX; x++) {
                for (int z = range.minZ; z <= range.maxZ; z++) {
                    result.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> new ArrayList<>()).add(box);
                }
            }
        }
        return result;
    }

    private record ChunkRange(int minX, int minZ, int maxX, int maxZ) {
        ChunkRange(AABB box) {
            this(SectionPos.blockToSectionCoord(box.minX), SectionPos.blockToSectionCoord(box.minZ),
                    SectionPos.blockToSectionCoord(box.maxX), SectionPos.blockToSectionCoord(box.maxZ));
        }

        long size() {
            return (maxX - (long) minX + 1) * (maxZ - (long) minZ + 1);
        }

        boolean contains(long key) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}
