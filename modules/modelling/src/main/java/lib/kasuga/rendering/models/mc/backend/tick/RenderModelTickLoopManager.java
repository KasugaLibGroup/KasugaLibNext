package lib.kasuga.rendering.models.mc.backend.tick;

import lib.kasuga.rendering.models.mc.dynamic.MinecraftModelRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Registers instance-owned pipelines with the single Minecraft frame scheduler. */
public abstract class RenderModelTickLoopManager<M> {
    // Do not store ModelTickLoop as a weak-key value: it strongly retains the instance.
    private final Map<ModelInstance, Boolean> registeredInstances = new WeakHashMap<>();

    /** Explicit compatibility fallback; automatic frames are owned by MinecraftModelRuntime. */
    @Deprecated
    public void onRenderFrame(RenderFrameEvent.Pre event) {
        tick(event.getPartialTick().getRealtimeDeltaTicks() / 20f);
    }

    /** Explicit fallback for registrations whose automatic scheduling has been disabled. */
    public void tick(float deltaTime) {
        for (ModelInstance instance : snapshot()) {
            if (instance.isClosed()) {
                unregisterLoop(instance);
            } else if (!MinecraftModelRuntime.isRegistered(instance)) {
                instance.tick(deltaTime);
            }
        }
    }

    public synchronized ModelTickLoop registerLoop(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance").requireOpen();
        registeredInstances.put(instance, Boolean.TRUE);
        MinecraftModelRuntime.register(instance);
        return instance.getTickLoop();
    }

    /** Scheduling ends here; destruction of the canonical loop belongs to ModelInstance.close(). */
    public synchronized void unregisterLoop(ModelInstance owner) {
        if (registeredInstances.remove(owner) != null) MinecraftModelRuntime.unregister(owner);
    }

    /**
     * Retained for source/binary compatibility with existing subclasses. Registration always uses
     * {@link ModelInstance#getTickLoop()} and never calls a factory that could create a second loop.
     */
    @Deprecated
    protected ModelTickLoop create(ModelInstance instance) {
        return Objects.requireNonNull(instance, "instance").getTickLoop();
    }

    private synchronized ArrayList<ModelInstance> snapshot() {
        return new ArrayList<>(registeredInstances.keySet());
    }
}
