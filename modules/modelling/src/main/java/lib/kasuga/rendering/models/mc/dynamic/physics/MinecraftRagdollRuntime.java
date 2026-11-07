package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.mc.dynamic.MinecraftModelRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsScene;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.Objects;

/** Compatibility facade for the standard model runtime; owns no event subscriptions or state. */
public final class MinecraftRagdollRuntime {
    private MinecraftRagdollRuntime() {}

    public static void register(ModelInstance instance, MinecraftRagdollConfig.UpdateMode updateMode) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(updateMode, "updateMode");
        if (updateMode == MinecraftRagdollConfig.UpdateMode.MANUAL) MinecraftModelRuntime.unregister(instance);
        else MinecraftModelRuntime.register(instance);
    }

    public static void unregister(ModelInstance instance) {
        MinecraftModelRuntime.unregister(instance);
    }

    public static void register(ModelPhysicsScene scene, MinecraftRagdollConfig.UpdateMode updateMode) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(updateMode, "updateMode");
        if (updateMode == MinecraftRagdollConfig.UpdateMode.MANUAL) MinecraftModelRuntime.unregister(scene);
        else MinecraftModelRuntime.register(scene);
    }

    public static void unregister(ModelPhysicsScene scene) {
        MinecraftModelRuntime.unregister(scene);
    }

    public static void step(ModelInstance instance, float deltaSeconds) {
        Objects.requireNonNull(instance, "instance").simulatePhysics(deltaSeconds);
    }

    public static int registeredCount() {
        return MinecraftModelRuntime.registeredCount();
    }

    static int registeredSceneCount() {
        return MinecraftModelRuntime.registeredSceneCount();
    }

    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        MinecraftModelRuntime.onRenderFrame(event);
    }

    public static void onClientLevelChanged(ClientTickEvent.Post event) {
        MinecraftModelRuntime.onClientLevelChanged(event);
    }
}
