package lib.kasuga.create.proxy;

import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link ElementProxy} for a bogey mounted on a train (a {@code CarriageBogey} drawn through
 * Create's {@code BogeyVisual} hook) — the contraption twin of {@link BogeyProxy}.
 *
 * <p>Same division of labour as the block-entity version: {@link BogeyModels} owns the model group
 * (which models, where), {@link #partsFor} is the per-element hook content overrides to attach a
 * spin or any other per-host data, and the root comes from the host, not from a block position.
 *
 * <p>The pose is a world-space root in this library's frame; see {@link ContraptionBogey#root()}
 * for how Create's pose stack maps onto it. The placement's own {@link BogeyModels.Placement#yOffset()}
 * is applied here, exactly once per read, on a copy.
 */
public class ContraptionBogeyProxy implements ElementProxy<ContraptionBogey> {

    @Override
    public @Nullable ProxyConfig configure(ContraptionBogey element) {
        BogeyModels.Placement placement = BogeyModels.resolve(element.styleId(), element.sizeId());
        if (placement == null) {
            return null;
        }
        List<ModelPart> parts = partsFor(element, placement);
        if (parts.isEmpty()) {
            return null;
        }
        float yOffset = placement.yOffset();
        element.placementYOffset(yOffset);
        return new ProxyConfig(null, null, null,
                element::normalizedRoot, parts);
    }

    /**
     * Parts to draw for one bogey — the hook content uses to bind per-host data, mirroring
     * {@link BogeyProxy#partsFor}.
     *
     * <p>A contraption bogey has no block entity, so a wheel angle can only come from the hook that
     * supplied it: {@link ContraptionBogey#wheelAngle()}.
     *
     * @param element   the bogey being configured
     * @param placement the registered model group for its style and size
     * @return the parts to bind, in draw order
     */
    protected List<ModelPart> partsFor(ContraptionBogey element, BogeyModels.Placement placement) {
        return placement.parts();
    }

    /**
     * Lift from Create's pose origin to the block centre our placement frame uses — the negation of
     * {@link BogeyRoots#CREATE_Y_OFFSET}, so the two hosts cannot drift apart in interpretation.
     *
     * <p>Create's own origin sits {@code 1.5 + 1/128} below the block centre: both
     * {@code BogeyBlockEntityVisual} and {@code CarriageContraptionVisual} apply that drop after
     * centring on the block, and {@code translateBogey}'s half block is the same centring the
     * block-entity visual does. So there is no contraption-specific correction — read this as "convert
     * Create's origin into the frame {@code BogeyRoots} produces", nothing more. Vertical adjustment
     * belongs to the placement's {@code yOffset}, which then applies to a placed bogey and a carriage
     * bogey alike; a per-host fudge is what made the two disagree in the first place.
     */
    public static final float CREATE_ORIGIN_LIFT = -BogeyRoots.CREATE_Y_OFFSET;

    /**
     * Root pose for a {@link ContraptionBogey}, from the pose stack Create's visual hook passes in.
     *
     * @param pose         the model-origin pose, in Flywheel's render-origin-relative space
     * @param renderOrigin the offset from world space to that render origin
     * @return a fresh transform in this library's world-space root frame
     */
    public static Transform rootOf(org.joml.Matrix4f pose, org.joml.Vector3i renderOrigin) {
        Transform root = new Transform().set(new org.joml.Matrix4f(pose));
        root.translateWorld(new org.joml.Vector3f(renderOrigin.x, renderOrigin.y, renderOrigin.z));
        return root.translate(0f, CREATE_ORIGIN_LIFT, 0f);
    }
}
