package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** A removable runtime ragdoll instance created by {@link MinecraftRagdollDeployments}. */
public interface RagdollDeployment extends AutoCloseable {
    ResourceLocation instanceId();
    ResourceLocation modelResource();
    String modelName();
    ResourceLocation configResource();
    ModelInstance instance();
    SkeletonRagdoll ragdoll();

    /** Whether this handle still owns a live pipeline instance. */
    boolean active();

    /**
     * The entity this ragdoll currently hangs from via its soft constraint,
     * or {@code null} when free. Mouse-dragging an anchored ragdoll cancels
     * the anchor.
     */
    @Nullable Entity anchoredEntity();

    /**
     * Attaches the closest dynamic body to the entity's eye position using
     * the same soft point constraint as mouse dragging, so gravity and joint
     * limits keep acting on the rest of the body.
     */
    boolean anchorTo(Entity entity);

    /**
     * Releases the entity anchor. No-op when not anchored. Returns true when
     * an active anchor was removed.
     */
    boolean detachAnchor();

    /** Removes the render instance and releases all attached ragdoll resources. */
    boolean remove();

    @Override
    default void close() {
        remove();
    }
}
