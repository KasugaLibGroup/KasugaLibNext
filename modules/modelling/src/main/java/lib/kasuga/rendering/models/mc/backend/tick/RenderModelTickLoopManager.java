package lib.kasuga.rendering.models.mc.backend.tick;

import jakarta.annotation.PostConstruct;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.LinkedHashMap;
import java.util.Map;

public abstract class RenderModelTickLoopManager<M> {
    private final Map<ModelInstance, ModelTickLoop> registeredLoops = new LinkedHashMap<>();

    @PostConstruct
    private void init() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderFrame(RenderFrameEvent.Pre event) {
        float deltaTime = event.getPartialTick().getRealtimeDeltaTicks() / 20.0f;
        tick(deltaTime);
    }

    public void tick(float deltaTime) {
        for (var loop : this.registeredLoops.values()) {
            loop.tick(deltaTime);
        }
    }

    public ModelTickLoop registerLoop(ModelInstance instance) {
        var loop = create(instance);
        this.registeredLoops.put(instance, loop);
        return loop;
    }

    public void unregisterLoop(ModelInstance owner) {
        var h = this.registeredLoops.remove(owner);
        if (h != null) {
            h.destroy();
        }
    }

    protected abstract ModelTickLoop create(ModelInstance instance);
}
