package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollDeployments;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollDragger;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsScene;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** The single automatic model-frame scheduler, independent of rendering and culling. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class MinecraftModelRuntime {
    // Values must not refer to their weak keys (a tick loop itself retains its instance).
    private static final Map<ModelInstance, Boolean> INSTANCES = new WeakHashMap<>();
    private static final Map<ModelPhysicsScene, Boolean> SCENES = new WeakHashMap<>();
    private static ClientLevel previousLevel;

    private MinecraftModelRuntime() {}

    public static synchronized void register(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance");
        if (instance.isClosed()) INSTANCES.remove(instance);
        else {
            INSTANCES.put(instance, Boolean.TRUE);
            ModelParticleRuntime.register(instance);
        }
    }

    public static synchronized void unregister(ModelInstance instance) {
        INSTANCES.remove(instance);
        ModelParticleRuntime.unregister(instance);
    }

    public static synchronized void register(ModelPhysicsScene scene) {
        Objects.requireNonNull(scene, "scene");
        if (scene.closed()) SCENES.remove(scene);
        else SCENES.put(scene, Boolean.TRUE);
    }

    public static synchronized void unregister(ModelPhysicsScene scene) {
        SCENES.remove(scene);
    }

    /** Includes membership in an explicitly registered or automatically inferred shared scene. */
    public static synchronized boolean isRegistered(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance");
        sweepClosed();
        if (instance.isClosed()) return false;
        if (INSTANCES.containsKey(instance)) return true;
        ModelPhysicsScene scene = activeScene(instance);
        return scene != null && isRegistered(scene);
    }

    public static synchronized boolean isRegistered(ModelPhysicsScene scene) {
        Objects.requireNonNull(scene, "scene");
        sweepClosed();
        if (scene.closed()) return false;
        if (SCENES.containsKey(scene)) return true;
        for (ModelInstance instance : INSTANCES.keySet()) {
            if (activeScene(instance) == scene) return true;
        }
        return false;
    }

    /** Counts direct registrations; inferred scene membership does not add registrations. */
    public static synchronized int registeredCount() {
        sweepClosed();
        return INSTANCES.size();
    }

    public static synchronized int registeredSceneCount() {
        sweepClosed();
        return SCENES.size();
    }

    /** Explicit manual pipeline step; does not advance the host's animation tick clock. */
    public static void step(ModelInstance instance, float deltaSeconds) {
        Objects.requireNonNull(instance, "instance");
        if (validDelta(deltaSeconds)) instance.tick(deltaSeconds);
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()) return;
        evaluateFrame(event.getPartialTick().getGameTimeDeltaPartialTick(true),
                event.getPartialTick().getRealtimeDeltaTicks() / 20f, true, false);
    }

    static void evaluateFrame(float partialTick, float deltaSeconds, boolean hasLevel, boolean paused) {
        if (hasLevel && !paused) evaluateFrame(partialTick, deltaSeconds);
    }

    /**
     * Advances one scheduled frame with host-supplied time. Shared scenes own their barrier;
     * standalone registrations retain automatic ownership when they migrate into a scene.
     * Explicit callers supply the active-world/pause policy; the Minecraft event does so above.
     */
    public static void evaluateFrame(float partialTick, float deltaSeconds) {
        if (!validDelta(deltaSeconds)) return;
        if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("partialTick must be finite");
        List<ModelInstance> instances = instanceSnapshot();
        Set<ModelPhysicsScene> evaluatedScenes = identitySet();
        Set<SkeletonRagdoll> evaluatedRelaxations = identitySet();

        // Scenes first: a later standalone callback must not sample over their completed pose.
        // Re-read membership each frame instead of retaining a scene across migrations.
        for (ModelPhysicsScene scene : sceneSnapshot()) {
            evaluateScene(scene, partialTick, deltaSeconds, evaluatedScenes, evaluatedRelaxations);
        }
        for (ModelInstance instance : instances) {
            if (!directlyRegistered(instance)) continue;
            SkeletonRagdoll relaxation = instance.getSkeletonInstance().getRelaxation();
            if (relaxation != null && evaluatedRelaxations.contains(relaxation)) continue;
            ModelPhysicsScene scene = activeScene(instance);
            if (scene != null) {
                evaluateScene(scene, partialTick, deltaSeconds, evaluatedScenes, evaluatedRelaxations);
            } else {
                instance.evaluateFrame(partialTick, deltaSeconds);
            }
        }
    }

    private static void evaluateScene(ModelPhysicsScene scene, float partialTick, float deltaSeconds,
                                      Set<ModelPhysicsScene> evaluatedScenes,
                                      Set<SkeletonRagdoll> evaluatedRelaxations) {
        if (scene.closed() || !isRegistered(scene) || !evaluatedScenes.add(scene)) return;
        // Capture before callbacks can migrate/detach participants. The identity still marks a
        // completed frame if its adapter moves to a different world during a later callback.
        List<SkeletonRagdoll> participants = scene.ragdolls().stream()
                .filter(state -> !state.closed()).toList();
        scene.evaluateFrame(partialTick, deltaSeconds);
        evaluatedRelaxations.addAll(participants);
    }

    private static ModelPhysicsScene activeScene(ModelInstance instance) {
        if (instance.isClosed()) return null;
        SkeletonRagdoll relaxation = instance.getSkeletonInstance().getRelaxation();
        if (relaxation == null || relaxation.closed()) return null;
        ModelPhysicsScene scene = relaxation.physicsScene();
        return scene == null || scene.closed() ? null : scene;
    }

    private static boolean validDelta(float deltaSeconds) {
        return deltaSeconds > 0f && Float.isFinite(deltaSeconds);
    }

    private static synchronized boolean directlyRegistered(ModelInstance instance) {
        sweepClosed();
        return INSTANCES.containsKey(instance);
    }

    private static synchronized List<ModelInstance> instanceSnapshot() {
        sweepClosed();
        return new ArrayList<>(INSTANCES.keySet());
    }

    private static synchronized List<ModelPhysicsScene> sceneSnapshot() {
        sweepClosed();
        return new ArrayList<>(SCENES.keySet());
    }

    private static void sweepClosed() {
        INSTANCES.keySet().removeIf(ModelInstance::isClosed);
        SCENES.keySet().removeIf(ModelPhysicsScene::closed);
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Leaving/changing worlds invalidates deployed coordinates, terrain caches and drag targets. */
    @SubscribeEvent
    public static void onClientLevelChanged(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == previousLevel) return;
        boolean hadLevel = previousLevel != null;
        previousLevel = level;
        MinecraftRagdollDragger.releaseActiveDrag();
        MinecraftRagdollDeployments.removeAll();
        if (hadLevel || level == null) ModelParticleRuntime.clearWorldState();
    }
}
