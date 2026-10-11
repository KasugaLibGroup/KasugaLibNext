package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RayHit;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Crosshair-driven mouse interaction for registered ragdolls. The selected
 * surface point stays at its original camera depth and is pulled by the
 * ragdoll's soft drag constraint rather than teleported.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class MinecraftRagdollDragger {
    private static final Map<ModelInstance, MinecraftRagdollConfig.Dragging> REGISTRATIONS
            = new WeakHashMap<>();
    private static ActiveDrag active;

    private MinecraftRagdollDragger() {}

    public static void register(ModelInstance instance, MinecraftRagdollConfig.Dragging settings) {
        REGISTRATIONS.put(Objects.requireNonNull(instance, "instance"),
                Objects.requireNonNull(settings, "settings"));
    }

    public static void unregister(ModelInstance instance) {
        REGISTRATIONS.remove(instance);
        if (active != null && active.instance == instance) release();
    }

    /**
     * Ends any in-progress drag regardless of owner. Called when the client
     * leaves a level: the captured camera depth and world target are invalid
     * in the next dimension.
     */
    public static void releaseActiveDrag() {
        if (active == null) return;
        release();
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.level == null) return;
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            if (active != null && event.getButton() == active.mouseButton) {
                release();
                event.setCanceled(true);
            }
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Vec3 origin = minecraft.gameRenderer.getMainCamera().getPosition();
        Vector3f direction = new Vector3f(minecraft.gameRenderer.getMainCamera().getLookVector());
        Pick closest = null;
        for (Map.Entry<ModelInstance, MinecraftRagdollConfig.Dragging> entry
                : REGISTRATIONS.entrySet()) {
            MinecraftRagdollConfig.Dragging settings = entry.getValue();
            if (!settings.enabled() || settings.mouseButton() != event.getButton()) continue;
            SkeletonRagdoll ragdoll = entry.getKey().getRagdoll();
            if (ragdoll == null || !ragdoll.enabled()) continue;
            RayHit hit = ragdoll.raycastWorld(origin.x, origin.y, origin.z,
                            direction, settings.maxDistance())
                    .orElse(null);
            if (hit != null && (closest == null || hit.distance() < closest.hit.distance())) {
                closest = new Pick(entry.getKey(), ragdoll, settings, hit);
            }
        }
        if (closest == null) return;
        if (active != null) release();
        // The mouse constraint and an entity anchor share the ragdoll's single
        // soft-constraint slot; grabbing with the mouse hands ownership over.
        MinecraftRagdollDeployments.cancelAnchorFor(closest.instance);
        if (!closest.ragdoll.beginDrag(closest.hit)) return;
        active = new ActiveDrag(closest.instance, closest.ragdoll,
                closest.settings.mouseButton(), closest.hit.distance());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (active == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        long window = minecraft.getWindow().getWindow();
        if (minecraft.screen != null || minecraft.level == null || !active.ragdoll.enabled()
                || GLFW.glfwGetMouseButton(window, active.mouseButton) != GLFW.GLFW_PRESS) {
            release();
            return;
        }
        Vec3 origin = minecraft.gameRenderer.getMainCamera().getPosition();
        Vector3f direction = new Vector3f(minecraft.gameRenderer.getMainCamera().getLookVector()).normalize();
        float deltaSeconds = event.getPartialTick().getRealtimeDeltaTicks() / 20f;
        active.ragdoll.updateDragTargetWorld(
                origin.x + direction.x * active.distance,
                origin.y + direction.y * active.distance,
                origin.z + direction.z * active.distance,
                deltaSeconds);
    }

    private static void release() {
        if (active == null) return;
        active.ragdoll.endDrag();
        active = null;
    }

    private record Pick(ModelInstance instance, SkeletonRagdoll ragdoll,
                        MinecraftRagdollConfig.Dragging settings, RayHit hit) {}

    private record ActiveDrag(ModelInstance instance, SkeletonRagdoll ragdoll,
                              int mouseButton, float distance) {}
}
