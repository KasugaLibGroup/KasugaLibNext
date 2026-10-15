package lib.kasuga.rendering.effect;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.effect.pipeline.CompiledRenderPipeline;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Registered world pipeline with managed, tickable and individually removable effects. */
public final class EffectRenderPipeline<T extends RenderEffect> implements PipelineRegistration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ResourceLocation owner;
    private final ResourceLocation id;
    private final RenderPipelineDescriptor descriptor;
    private final EffectRenderer<T> renderer;
    private final boolean sortBackToFront;
    private final ConcurrentLinkedQueue<Slot<T>> pending = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> mutations = new ConcurrentLinkedQueue<>();
    private final List<Slot<T>> active = new ArrayList<>();
    private final List<Slot<T>> visibleScratch = new ArrayList<>();
    private final PipelineRegistration registration;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int lastVisibleCount;
    private volatile long lastRenderNanos;

    private EffectRenderPipeline(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            boolean sortBackToFront,
            EffectRenderer<T> renderer
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        id = descriptor.id();
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.sortBackToFront = sortBackToFront;
        registration = WorldRenderPipelineRegistry.register(
                owner, descriptor, duplicatePolicy, this::render
        );
        try {
            ClientEffectRuntime.add(this, duplicatePolicy);
        } catch (RuntimeException exception) {
            registration.close();
            throw exception;
        }
    }

    public static <T extends RenderEffect> EffectRenderPipeline<T> register(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            boolean sortBackToFront,
            EffectRenderer<T> renderer
    ) {
        return new EffectRenderPipeline<>(owner, descriptor, duplicatePolicy, sortBackToFront, renderer);
    }

    /** Thread-safe. The instance becomes visible no later than the next render invocation. */
    public EffectHandle<T> spawn(T effect) {
        if (closed.get()) throw new IllegalStateException("Effect pipeline is closed: " + id);
        Slot<T> slot = new Slot<>(Objects.requireNonNull(effect, "effect"));
        activeCount.incrementAndGet();
        pending.add(slot);
        if (closed.get() && slot.remove()) {
            pending.remove(slot);
            throw new IllegalStateException("Effect pipeline is closed: " + id);
        }
        return slot;
    }

    void tick(ClientLevel level) {
        drainMutations();
        drainPending();
        for (int index = active.size() - 1; index >= 0; index--) {
            Slot<T> slot = active.get(index);
            T effect = slot.effect;
            if (!slot.live.get() || !effect.isAlive()) {
                deactivate(slot);
                active.remove(index);
                continue;
            }
            try {
                effect.tick(level);
            } catch (RuntimeException exception) {
                deactivate(slot);
                active.remove(index);
                LOGGER.error("Effect instance in pipeline {} failed during tick and was removed", id, exception);
                continue;
            }
            if (!effect.isAlive()) {
                deactivate(slot);
                active.remove(index);
            }
        }
    }

    private void render(WorldRenderPipelineContext context) {
        drainMutations();
        drainPending();
        if (active.isEmpty()) {
            lastVisibleCount = 0;
            lastRenderNanos = 0;
            return;
        }

        long started = System.nanoTime();
        float partialTick = context.partialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPosition = context.camera().getPosition();
        visibleScratch.clear();
        for (Slot<T> slot : active) {
            T effect = slot.effect;
            if (!slot.live.get() || !effect.isAlive()) continue;
            if (!effect.isVisible(context, partialTick)) continue;
            visibleScratch.add(slot);
        }
        if (sortBackToFront && visibleScratch.size() > 1) {
            for (Slot<T> slot : visibleScratch) {
                slot.sortDistanceSqr = slot.effect.distanceToSqr(partialTick, cameraPosition);
            }
            visibleScratch.sort((left, right) ->
                    Double.compare(right.sortDistanceSqr, left.sortDistanceSqr)
            );
        }

        lastVisibleCount = visibleScratch.size();
        if (visibleScratch.isEmpty()) {
            lastRenderNanos = System.nanoTime() - started;
            return;
        }

        renderer.begin(context);
        try {
            for (Slot<T> slot : visibleScratch) {
                if (!slot.live.get()) continue;
                try {
                    renderer.render(slot.effect, context);
                } catch (RuntimeException exception) {
                    LOGGER.error("Effect instance in pipeline {} failed during rendering", id, exception);
                }
            }
        } finally {
            renderer.end(context);
            lastRenderNanos = System.nanoTime() - started;
        }
    }

    private void drainPending() {
        Slot<T> slot;
        while ((slot = pending.poll()) != null) {
            if (slot.live.get() && slot.effect.isAlive()) active.add(slot);
            else deactivate(slot);
        }
    }

    private void drainMutations() {
        Runnable mutation;
        while ((mutation = mutations.poll()) != null) mutation.run();
    }

    private void deactivate(Slot<T> slot) {
        if (slot.live.compareAndSet(true, false)) activeCount.decrementAndGet();
    }

    public int activeCount() { return activeCount.get(); }
    public int lastVisibleCount() { return lastVisibleCount; }
    public long lastRenderNanos() { return lastRenderNanos; }

    public String effectTypeName() {
        Slot<T> current = active.isEmpty() ? pending.peek() : active.getFirst();
        return current == null ? "-" : current.effect.getClass().getSimpleName();
    }

    @Override public ResourceLocation id() { return id; }
    @Override public ResourceLocation owner() { return owner; }
    @Override public boolean isActive() { return !closed.get() && registration.isActive(); }
    @Override public RenderPipelineDescriptor descriptor() { return descriptor; }
    @Override public CompiledRenderPipeline compiledPipeline() { return registration.compiledPipeline(); }

    /** Thread-safe; removal is applied on the next tick or render traversal. */
    public void clear() {
        if (closed.get()) return;
        mutations.add(this::clearImmediately);
    }

    void clearImmediately() {
        pending.forEach(this::deactivate);
        pending.clear();
        active.forEach(this::deactivate);
        active.clear();
        visibleScratch.clear();
        lastVisibleCount = 0;
        lastRenderNanos = 0;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ClientEffectRuntime.remove(this);
        registration.close();
        pending.forEach(this::deactivate);
        pending.clear();
        mutations.clear();
    }

    private final class Slot<E extends T> implements EffectHandle<T> {
        private final T effect;
        private final AtomicBoolean live = new AtomicBoolean(true);
        private double sortDistanceSqr;

        private Slot(T effect) {
            this.effect = effect;
        }

        @Override public T effect() { return effect; }
        @Override public boolean isActive() { return live.get() && !closed.get(); }

        @Override
        public boolean remove() {
            if (!live.compareAndSet(true, false)) return false;
            activeCount.decrementAndGet();
            return true;
        }
    }
}
