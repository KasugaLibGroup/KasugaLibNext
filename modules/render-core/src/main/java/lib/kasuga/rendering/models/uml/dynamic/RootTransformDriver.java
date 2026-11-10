package lib.kasuga.rendering.models.uml.dynamic;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/**
 * Render-frame writer for one model instance's root transform.
 *
 * <p>This is deliberately separate from {@link PoseDriver}: a root driver never advances animation
 * time or writes bones, morphs, materials, or IK. The owning host publishes logical root state on
 * its tick thread; {@link #sampleRoot(float)} only composes that state for the current frame.
 */
public interface RootTransformDriver extends AutoCloseable {

    /**
     * The model instance this driver is currently allowed to write, or {@code null} for a stateless
     * driver that has not attached yet. A driver owned by one instance cannot be installed on another.
     */
    default @Nullable ModelInstance owner() {
        return null;
    }

    /** Writes this frame's root transform by interpolating with {@code partialTick}. */
    void sampleRoot(float partialTick);

    /**
     * Optional world-space point used to sample lighting when the visual root is not a suitable
     * light probe. Returning {@code null} keeps the normal root-position behavior.
     */
    default @Nullable Vector3dc worldLightPosition() {
        return null;
    }

    /** Detaches this non-terminal driver from a model that is being destroyed or reloaded. */
    default void detach(ModelInstance instance) {
    }

    /** Releases any driver-owned resources. The default implementation has none. */
    @Override
    default void close() {
    }
}
