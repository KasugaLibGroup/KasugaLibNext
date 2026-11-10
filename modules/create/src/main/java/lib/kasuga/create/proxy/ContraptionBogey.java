package lib.kasuga.create.proxy;

import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The host data a contraption (train) bogey can offer the model pipeline, expressed the way
 * {@link lib.kasuga.rendering.models.mc.proxies.ElementProxy} wants it.
 *
 * <p>A bogey on a train is not a block entity: it is a {@code CarriageBogey} owned by a
 * {@code Carriage}, and the only client-side hook it exposes is Create's
 * {@code BogeyVisual#update(CompoundTag, float wheelAngle, PoseStack)}. That hook hands over three
 * things — the style (inside {@code bogeyData}), the interpolated wheel angle, and a pose stack
 * positioned at Create's own model origin — and this record-ish carrier is where they land, so the
 * rest of the proxy layer does not need to know about trains.
 *
 * <p>The root and wheel angle are published together as one immutable {@link HostFrame}. Readers
 * take one volatile snapshot, so a render sample can never combine two different host frames.
 *
 * <h2>Coordinate frame</h2>
 * <p>{@link HostFrame#root()} is already in the proxy's frame: Create's model origin (the pose
 * stack it passes to {@code update}) plus the {@code 1.5 + 1/128} blocks that separate it from the
 * block centre our placement math uses. A placement's own vertical offset is applied on top of this
 * by {@link ContraptionBogeyProxy}, not here.
 */
public final class ContraptionBogey {

    /**
     * One atomically published Create frame. The constructor and accessor both copy the root, so
     * neither the visual nor a consumer can mutate another frame's Transform.
     */
    public record HostFrame(float wheelAngle, Transform root) {

        public HostFrame {
            if (!Float.isFinite(wheelAngle)) {
                throw new IllegalArgumentException("wheelAngle must be finite");
            }
            root = finiteCopy(root);
        }

        @Override
        public Transform root() {
            return root.copy();
        }
    }

    private final ResourceLocation styleId;
    private final ResourceLocation sizeId;

    private volatile HostFrame hostFrame;
    private volatile float placementYOffset;

    public ContraptionBogey(ResourceLocation styleId, ResourceLocation sizeId, float wheelAngle, Transform root) {
        this.styleId = Objects.requireNonNull(styleId, "styleId");
        this.sizeId = Objects.requireNonNull(sizeId, "sizeId");
        this.hostFrame = new HostFrame(wheelAngle, root);
    }

    /** Id of the committed {@code BogeyStyle}, read back from the bogey's NBT. */
    public ResourceLocation styleId() {
        return styleId;
    }

    /** Id of the bogey size this visual was registered for. */
    public ResourceLocation sizeId() {
        return sizeId;
    }

    /** Wheel angle in degrees from the latest atomically published frame. */
    public float wheelAngle() {
        return hostFrame().wheelAngle();
    }

    /** The latest immutable root/angle pair; root accessors return defensive copies. */
    public HostFrame hostFrame() {
        return hostFrame;
    }

    /** World-space root pose in the proxy's frame; callers must copy it before composing. */
    public Transform root() {
        return hostFrame().root();
    }

    /** Stores the registered placement offset used by both tick suppliers and visual frames. */
    public void placementYOffset(float yOffset) {
        if (!Float.isFinite(yOffset)) {
            throw new IllegalArgumentException("placement yOffset must be finite");
        }
        this.placementYOffset = yOffset;
    }

    /** Placement offset currently applied by {@link #normalizedRoot()}. */
    public float placementYOffset() {
        return placementYOffset;
    }

    /** Root from one host snapshot with the placement's vertical offset applied exactly once. */
    public Transform normalizedRoot() {
        return normalizedRoot(hostFrame());
    }

    /** Applies the registered placement offset to the supplied immutable host snapshot. */
    public Transform normalizedRoot(HostFrame frame) {
        return frame.root().translate(0f, placementYOffset, 0f);
    }

    /** Publishes one complete Create host frame. Client thread only. */
    public void update(float wheelAngle, Transform root) {
        publishFrame(wheelAngle, root, ignored -> {
        });
    }

    /**
     * Atomically publishes a frame and runs the callback while the frame lock is held. A subsequent
     * update cannot replace the root/angle pair between the callback's reads.
     */
    public synchronized void publishFrame(
            float wheelAngle,
            Transform root,
            Consumer<HostFrame> callback
    ) {
        HostFrame frame = new HostFrame(wheelAngle, root);
        hostFrame = frame;
        Objects.requireNonNull(callback, "callback").accept(frame);
    }

    private static Transform finiteCopy(Transform root) {
        Transform copy = Objects.requireNonNull(root, "root").copy();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                if (!Float.isFinite(copy.transform().get(row, column))) {
                    throw new IllegalArgumentException("root transform must be finite");
                }
            }
        }
        return copy;
    }
}
