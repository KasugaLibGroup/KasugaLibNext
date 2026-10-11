package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Advances configured ragdolls independently of backend visibility and batching. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class MinecraftRagdollRuntime {
    private static final Map<ModelInstance, MinecraftRagdollConfig.UpdateMode> INSTANCES =
            new WeakHashMap<>();
    private static final Map<ModelPhysicsScene, MinecraftRagdollConfig.UpdateMode> SCENES =
            new WeakHashMap<>();
    private static ClientLevel previousLevel;

    private MinecraftRagdollRuntime() {}

    public static synchronized void register(ModelInstance instance,
                                             MinecraftRagdollConfig.UpdateMode updateMode) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(updateMode, "updateMode");
        if (updateMode == MinecraftRagdollConfig.UpdateMode.MANUAL) {
            INSTANCES.remove(instance);
        } else {
            INSTANCES.put(instance, updateMode);
        }
    }

    public static synchronized void unregister(ModelInstance instance) {
        INSTANCES.remove(instance);
    }

    public static synchronized void register(ModelPhysicsScene scene,
                                             MinecraftRagdollConfig.UpdateMode updateMode) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(updateMode, "updateMode");
        if (updateMode == MinecraftRagdollConfig.UpdateMode.MANUAL) SCENES.remove(scene);
        else SCENES.put(scene, updateMode);
    }

    public static synchronized void unregister(ModelPhysicsScene scene) {
        SCENES.remove(scene);
    }

    /** Explicit stepping entry point for MANUAL mode and non-render integrations. */
    public static void step(ModelInstance instance, float deltaSeconds) {
        Objects.requireNonNull(instance, "instance").simulatePhysics(deltaSeconds);
    }

    public static synchronized int registeredCount() {
        return INSTANCES.size();
    }

    static synchronized int registeredSceneCount() {
        SCENES.keySet().removeIf(ModelPhysicsScene::closed);
        return SCENES.size();
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()) return;
        float deltaSeconds = event.getPartialTick().getRealtimeDeltaTicks() / 20f;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        // Copy weak keys before stepping: collision environments and callbacks
        // may indirectly mutate registrations while a ragdoll is updating.
        for (ModelInstance instance : snapshot()) {
            instance.evaluatePhysicsFrame(partialTick, deltaSeconds);
        }
        for (ModelPhysicsScene scene : sceneSnapshot()) {
            if (!scene.closed()) scene.evaluateFrame(partialTick, deltaSeconds);
        }
    }

    /**
     * Deployed ragdolls are world-anchored: leaving a level (disconnect or
     * dimension change) invalidates their terrain cache, drag targets and
     * coordinates, so every live deployment is torn down exactly once per
     * transition. Non-deployment ragdolls survive; their collision
     * environments re-attach to the next level lazily.
     */
    @SubscribeEvent
    public static void onClientLevelChanged(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == previousLevel) return;
        previousLevel = level;
        MinecraftRagdollDragger.releaseActiveDrag();
        MinecraftRagdollDeployments.removeAll();
    }

    private static synchronized ArrayList<ModelInstance> snapshot() {
        return new ArrayList<>(INSTANCES.keySet());
    }

    private static synchronized ArrayList<ModelPhysicsScene> sceneSnapshot() {
        SCENES.keySet().removeIf(ModelPhysicsScene::closed);
        return new ArrayList<>(SCENES.keySet());
    }
}
