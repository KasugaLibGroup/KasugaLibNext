package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Placement transform for a placed bogey block, expressed in the model pipeline's root space.
 *
 * <p>The values mirror Create's own visual, which is the authoritative answer for where a bogey
 * sits. {@code BogeyBlockEntityVisual} builds its pose as:
 *
 * <pre>{@code
 * translate(visualPos);
 * translate(.5f, .5f, .5f);
 * if (axis == X) mulPose(Axis.YP.rotationDegrees(90));
 * translate(0, -1.5 - 1 / 128f, 0);
 * }</pre>
 *
 * <p>This class reproduces the same sequence with {@link Transform}, whose {@code translate} and
 * {@code rotate} compose in local space like {@code PoseStack}. Net translation is
 * {@code (x + .5, y - 1 - 1/128, z + .5)}; the wheel drop of 1.5 blocks is applied after the
 * block's half-block centering.
 *
 * <p>Only used by {@code ProxyConfig#rootTransform} on the state-machine path. Hosts that only
 * bind a model do not consume it (see the implementation guide).
 */
public final class BogeyRoots {

    /**
     * Vertical offset Create uses for its own bogey models: their origin sits at the rail plane and
     * the geometry hangs below it. Models authored with the origin at the base or centre need a
     * different value; pass it through {@link BogeyModels#register(ResourceLocation, ResourceLocation, float)}.
     */
    public static final float CREATE_Y_OFFSET = -1.5f - 1f / 128f;

    /**
     * Builds the root transform for a placed bogey using Create's vertical convention.
     *
     * @param element the block entity being configured; only its position and {@code AXIS} are read
     * @return a fresh transform owned by the caller
     */
    public static Transform of(AbstractBogeyBlockEntity element) {
        return of(element, CREATE_Y_OFFSET);
    }

    /**
     * Builds the root transform for a placed bogey.
     *
     * @param element the block entity being configured; only its position and {@code AXIS} are read
     * @param yOffset vertical offset from the block centre, in blocks
     * @return a fresh transform owned by the caller
     */
    public static Transform of(AbstractBogeyBlockEntity element, float yOffset) {
        BlockPos pos = element.getBlockPos();
        Transform transform = new Transform()
                .translate(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
        if (element.getBlockState().getValue(AbstractBogeyBlock.AXIS) == Direction.Axis.X) {
            transform.rotate(0f, 90f, 0f, true);
        }
        transform.translate(0f, yOffset, 0f);
        return transform;
    }

    private BogeyRoots() {
    }
}
