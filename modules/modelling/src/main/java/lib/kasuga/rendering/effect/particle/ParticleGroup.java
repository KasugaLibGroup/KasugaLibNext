package lib.kasuga.rendering.effect.particle;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns transformable instances and performs optional two-phase behavior updates. Instance state is
 * snapshotted first; all controller/behavior results are committed afterward.
 */
public final class ParticleGroup {
    private final Object lock = new Object();
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private volatile ParticleGroupBehavior controller = ParticleGroupBehavior.NONE;
    private volatile ParticleBufferGroupBehavior bufferController;
    private final ParticleInstanceBuffer currentBuffer = new ParticleInstanceBuffer(256);
    private final ParticleInstanceBuffer nextBuffer = new ParticleInstanceBuffer(256);
    private final org.joml.Matrix4f matrixScratch = new org.joml.Matrix4f();

    public ParticleHandle add(ParticleInstance instance) {
        Objects.requireNonNull(instance, "instance");
        long id = nextId.incrementAndGet();
        Entry entry = new Entry(id, instance);
        synchronized (lock) {
            entries.put(id, entry);
        }
        return entry;
    }

    public void controller(ParticleGroupBehavior value) {
        controller = Objects.requireNonNull(value, "value");
    }

    public ParticleGroupBehavior controller() {
        return controller;
    }

    public void bufferController(ParticleBufferGroupBehavior value) {
        bufferController = Objects.requireNonNull(value, "value");
    }

    public ParticleBufferGroupBehavior bufferController() {
        return bufferController;
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.values().forEach(entry -> entry.active.set(false));
            entries.clear();
        }
    }

    public ParticleGroupSnapshot snapshot() {
        synchronized (lock) {
            List<ParticleSnapshot> snapshots = new ArrayList<>(entries.size());
            entries.forEach((id, entry) -> {
                if (entry.active.get()) snapshots.add(entry.instance.snapshot(id));
            });
            return new ParticleGroupSnapshot(snapshots);
        }
    }

    void writeVisible(ParticleInstanceBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        synchronized (lock) {
            entries.forEach((id, entry) -> {
                if (entry.active.get()) entry.instance.write(id, destination, true);
            });
        }
    }

    public void update(ClientLevel level) {
        ParticleBufferGroupBehavior packed = bufferController;
        if (packed != null) updateBuffer(packed, level);
        if (controller == ParticleGroupBehavior.NONE && !hasInstanceBehaviors()) return;
        ParticleGroupSnapshot snapshot = snapshot();
        if (snapshot.isEmpty()) return;

        Map<Long, ParticleUpdate> updates = new LinkedHashMap<>();
        controller.update(snapshot, (id, update) -> {
            snapshot.require(id);
            updates.put(id, Objects.requireNonNull(update, "update"));
        }, level);

        List<BehaviorEntry> behaviors = behaviorSnapshot();
        for (BehaviorEntry entry : behaviors) {
            ParticleSnapshot particle = snapshot.find(entry.id).orElse(null);
            if (particle == null) continue;
            ParticleUpdate update = entry.behavior.update(particle, snapshot, level);
            if (update != null) updates.put(entry.id, update);
        }

        if (updates.isEmpty()) return;
        synchronized (lock) {
            updates.forEach((id, update) -> {
                Entry entry = entries.get(id);
                if (entry == null || !entry.active.get()) return;
                if (update.removesInstance()) {
                    entry.active.set(false);
                    entries.remove(id);
                } else {
                    entry.instance.apply(update);
                }
            });
        }
    }

    private void updateBuffer(ParticleBufferGroupBehavior packed, ClientLevel level) {
        currentBuffer.beginWrite();
        synchronized (lock) {
            entries.forEach((id, entry) -> {
                if (entry.active.get()) entry.instance.write(id, currentBuffer, false);
            });
        }
        if (currentBuffer.isEmpty()) return;
        nextBuffer.copyFrom(currentBuffer);
        packed.update(currentBuffer, nextBuffer, level);
        synchronized (lock) {
            int index = 0;
            java.util.Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (!entry.active.get()) continue;
                if (index >= nextBuffer.size() || nextBuffer.id(index) != entry.id) {
                    throw new IllegalStateException("Particle packed update changed instance ordering");
                }
                if (nextBuffer.removed(index)) {
                    entry.active.set(false);
                    iterator.remove();
                } else {
                    entry.instance.apply(nextBuffer, index, matrixScratch);
                }
                index++;
            }
        }
    }

    private boolean hasInstanceBehaviors() {
        synchronized (lock) {
            for (Entry entry : entries.values()) {
                if (entry.active.get() && entry.instance.behavior() != null) return true;
            }
            return false;
        }
    }

    private List<BehaviorEntry> behaviorSnapshot() {
        synchronized (lock) {
            List<BehaviorEntry> result = new ArrayList<>();
            entries.forEach((id, entry) -> {
                if (!entry.active.get()) return;
                ParticleBehavior behavior = entry.instance.behavior();
                if (behavior != null) result.add(new BehaviorEntry(id, behavior));
            });
            return result;
        }
    }

    private record BehaviorEntry(long id, ParticleBehavior behavior) {
    }

    private final class Entry implements ParticleHandle {
        private final long id;
        private final ParticleInstance instance;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Entry(long id, ParticleInstance instance) {
            this.id = id;
            this.instance = instance;
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public ParticleInstance instance() {
            return instance;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public boolean remove() {
            if (!active.compareAndSet(true, false)) return false;
            synchronized (lock) {
                entries.remove(id, this);
            }
            return true;
        }
    }
}
