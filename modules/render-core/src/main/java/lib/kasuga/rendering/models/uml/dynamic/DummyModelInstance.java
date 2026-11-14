package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.AnchorModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A private, transform-only rig: one identity root, no vertices, meshes, materials or textures.
 * Anchors may be authored or moved at runtime without affecting any other dummy instance.
 *
 * <p>Like other model instances, this object is owned by one host thread. Use the normal root,
 * pose and tick-loop APIs to move it. A host must evaluate it before its children each frame;
 * Minecraft's {@code McDummyModels.create()} supplies automatic frame registration. Manual
 * hosts can call {@link #evaluateFrame(float, float)} or {@link #tick(float)}. Closing a group
 * detaches its children but leaves their instances and render resources owned by the caller.</p>
 */
public class DummyModelInstance extends ModelInstance {
    private final Map<ModelInstance, Attachment> children = new IdentityHashMap<>();

    public DummyModelInstance() {
        this(null);
    }

    public DummyModelInstance(@Nullable Transform rootTransform) {
        super(emptyRig(), rootTransform == null ? null : rootTransform.copy(), null, null, null, null);
    }

    private static Model emptyRig() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Bone[] bones = {root};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        return new Model(new Vertex[0], new Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null) {
            @Override public boolean isRenderable() { return false; }
        };
    }

    /** Adds a uniquely named root-local anchor. The input transform is copied. */
    public Anchor defineAnchor(String name, Transform localTransform) {
        requireOpen();
        Transform offset = checkedCopy(localTransform);
        var skeleton = getModel().getSkeleton();
        Anchor anchor = skeleton.defineAnchor(name, skeleton.getRoot(), offset);
        forceUpdate();
        return anchor;
    }

    /** Moves an existing anchor in root-local space, retaining all its attachments. */
    public void setAnchorTransform(String name, Transform localTransform) {
        requireOpen();
        Anchor anchor = requireAnchor(name);
        anchor.setTransform(getModel().getSkeleton().getBindingAbsolute(
                getModel().getSkeleton().getRoot()).copy().mul(checkedCopy(localTransform)));
        forceUpdate();
    }

    public Attachment attach(String anchorName, ModelInstance child) {
        return attach(anchorName, child, new Transform());
    }

    /**
     * Places a child's root at {@code parent * anchor * localOffset}. The child keeps its own
     * internal animation. One root owner is allowed; detach an old attachment/root driver first.
     * Scope-owned animation ports must compose their root inside that scope instead.
     */
    public Attachment attach(String anchorName, ModelInstance child, Transform localOffset) {
        requireOpen();
        Objects.requireNonNull(child, "child").requireOpen();
        requireAnchor(anchorName);
        Transform offset = checkedCopy(localOffset);
        for (ModelInstance ancestor = this; ancestor != null; ancestor = parentOf(ancestor)) {
            if (ancestor == child) throw new IllegalArgumentException("Model attachments must not form a cycle");
        }
        if (child.getRootTransformDriver() != null || child.getAnimationRenderPort() != null) {
            throw new IllegalStateException("Child already has a root driver or scope-owned animation port");
        }
        Attachment attachment = new Attachment(anchorName, child, offset);
        child.setRootTransformDriver(attachment);
        children.put(child, attachment);
        if (!attachToAnchor(anchorName, attachment.callback)) {
            attachment.close();
            throw new IllegalStateException("The anchor tick module has been removed");
        }
        try {
            // Resolve the current pose immediately, including groups created after the host's
            // frame event. No animation clock is advanced by this zero-delta update.
            update();
        } catch (RuntimeException | Error failure) {
            attachment.close();
            throw failure;
        }
        return attachment;
    }

    public boolean detach(ModelInstance child) {
        requireOpen();
        Attachment attachment = children.get(child);
        if (attachment == null) return false;
        attachment.close();
        return true;
    }

    public int attachmentCount() {
        return children.size();
    }

    /** The declared frame dependency used by schedulers; null for an unattached instance. */
    public static @Nullable DummyModelInstance parentOf(ModelInstance instance) {
        return instance.getRootTransformDriver() instanceof Attachment attachment && !attachment.closed
                ? attachment.parent() : null;
    }

    private Anchor requireAnchor(String name) {
        Anchor anchor = getModel().getSkeleton().getAnchor(Objects.requireNonNull(name, "name"));
        if (anchor == null) throw new IllegalArgumentException("Unknown dummy anchor: " + name);
        return anchor;
    }

    private static Transform checkedCopy(Transform transform) {
        Objects.requireNonNull(transform, "transform");
        if (!transform.transform().isFinite() || !transform.normal().isFinite()) {
            throw new IllegalArgumentException("Attachment transforms must be finite");
        }
        return transform.copy();
    }

    @Override
    public void close() {
        if (isClosed()) return;
        for (Attachment attachment : new ArrayList<>(children.values())) attachment.close();
        super.close();
    }

    /** A non-owning child attachment. Closing it preserves the child's last root pose. */
    public final class Attachment implements RootTransformDriver {
        private final String anchorName;
        private final ModelInstance child;
        // Re-evaluate relative to the child's origin, rather than round a world-space float.
        private final AnchorModule.Attachment callback = ignored -> apply();
        private Transform offset;
        private boolean closed;

        private Attachment(String anchorName, ModelInstance child, Transform offset) {
            this.anchorName = anchorName;
            this.child = child;
            this.offset = offset;
        }

        public DummyModelInstance parent() { return DummyModelInstance.this; }
        @Override public ModelInstance owner() { return child; }
        public boolean isClosed() { return closed; }

        public void setOffset(Transform localOffset) {
            parent().requireOpen();
            if (closed) throw new IllegalStateException("Attachment is closed");
            offset = checkedCopy(localOffset);
            parent().forceUpdate();
        }

        @Override
        public void sampleRoot(float partialTick) {
            if (closed) return;
            // Also supports a child rendered by a manual host. Prepared automatic frames are
            // reused, so drawing several children does not resample the parent's animation.
            parent().prepareFrameSample(partialTick);
            parent().update();
            apply();
        }

        private void apply() {
            if (closed) return;
            if (child.isClosed()) { close(); return; }
            SkeletonInstance target = child.getSkeletonInstance();
            SkeletonInstance source = parent().getSkeletonInstance();
            if (source.isFloatingOriginEnabled() || target.isFloatingOriginEnabled()) {
                Vector3d origin = source.isFloatingOriginEnabled()
                        ? source.getWorldOrigin() : source.getWorldRootPosition();
                if (!target.isFloatingOriginEnabled()) target.enableFloatingOrigin(origin);
                else if (!target.getWorldOrigin().equals(origin, 0.0)) target.rebaseFloatingOrigin(origin);
            }
            Vector3d origin = target.isFloatingOriginEnabled() ? target.getWorldOrigin() : new Vector3d();
            Transform anchor = source.anchorTransformRelative(anchorName, origin);
            if (anchor != null) target.transformRoot(anchor.mul(offset));
        }

        @Override
        public void detach(ModelInstance instance) {
            if (instance != child || closed) return;
            closed = true;
            children.remove(child, this);
            parent().detachFromAnchor(anchorName, callback);
        }

        @Override
        public void close() {
            if (child.getRootTransformDriver() == this) child.setRootTransformDriver(null);
            else detach(child);
        }
    }
}
