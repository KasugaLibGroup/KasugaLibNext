package lib.kasuga.create.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.bogey.BogeyRenderer;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyVisual;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import lib.kasuga.create.proxy.BogeyModels;
import lib.kasuga.create.proxy.ContraptionBogey;
import lib.kasuga.create.proxy.ContraptionBogeyProxy;
import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ProxyIdentity;
import lib.kasuga.rendering.models.mc.proxies.ProxyInstance;
import lib.kasuga.rendering.models.mc.proxies.Proxies;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * {@link BogeyVisual} that feeds Create's per-frame wheel angle and pose to the proxy layer instead
 * of drawing through Flywheel — the train-side twin of the block-entity path.
 *
 * <p>Create gives a bogey visual exactly three things per frame: the style (inside {@code
 * bogeyData}), the interpolated wheel angle, and a pose stack positioned at its own model origin.
 * Those are the only client-side facts a {@code CarriageBogey} offers, and they are enough: this
 * class republishes them into a {@link ContraptionBogey} and lets an ordinary
 * {@link ProxyInstance} bind, pose and release the models. Nothing here draws.
 *
 * <h2>Why the proxy is created lazily</h2>
 * <p>The style id lives in {@code bogeyData}, which is only available in {@link #update}. The first
 * update resolves it, creates the {@link ContraptionBogey} (and with it the proxy and its parts),
 * and every later update just publishes the new angle and pose.
 *
 * <h2>Ticking</h2>
 * <p>{@link #update} runs once per <b>frame</b> from Flywheel's {@code beginFrame}, so it only
 * republishes data. The proxy itself is advanced once per game tick by
 * {@link ContraptionBogeyTickers}, exactly like the block-entity host — binding models mid-frame
 * would create pipeline instances during the render phase.
 *
 * <h2>Coexistence</h2>
 * <p>Register this together with {@link NoopRenderer}: the vanilla renderer path
 * ({@code !VisualizationManager.supportsVisualization}) would otherwise draw Create's own model on
 * top of ours. With Flywheel active (the normal case) the entity renderer is skipped entirely.
 *
 * @see ContraptionBogeyProxy
 */
public final class ProxyBogeyVisual implements BogeyVisual {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong IDS = new AtomicLong();

    private final VisualizationContext context;
    private final BogeySizes.BogeySize size;
    private final ElementProxy<ContraptionBogey> template;
    private final ProxyIdentity identity;

    @Nullable private ContraptionBogey element;
    @Nullable private ProxyInstance<ContraptionBogey> proxy;
    private int light;

    /**
     * @param context  Flywheel's context; its render origin converts the pose to world space
     * @param size     the size this visual was registered for (Create does not pass it to visuals)
     * @param template the element proxy, normally a {@link ContraptionBogeyProxy} subclass that
     *                 binds the wheel angle
     */
    public ProxyBogeyVisual(VisualizationContext context, BogeySizes.BogeySize size,
                            ElementProxy<ContraptionBogey> template) {
        this.context = context;
        this.size = size;
        this.template = template;
        this.identity = ProxyIdentity.ofExternal("kasuga_lib", "contraption_bogey", IDS.incrementAndGet());
    }

    @Override
    public void update(CompoundTag bogeyData, float wheelAngle, PoseStack poseStack) {
        ContraptionBogey bogey = element;
        if (bogey == null) {
            createElement(bogeyData, wheelAngle, poseStack);
            return;
        }
        Transform root = rootOf(poseStack);
        bogey.publishFrame(wheelAngle, root, frame -> {
            ProxyInstance<ContraptionBogey> instance = proxy;
            if (instance != null) {
                instance.publishVisualFrame(bogey.normalizedRoot(frame));
            }
        });
    }

    private @Nullable ContraptionBogey createElement(CompoundTag bogeyData, float wheelAngle, PoseStack poseStack) {
        ResourceLocation styleId = BogeyModels.styleIdOf(bogeyData);
        if (styleId == null) {
            LOGGER.debug("[proxy] contraption bogey visual has no style id in its NBT; skipping");
            return null;
        }
        ContraptionBogey created = new ContraptionBogey(styleId, size.id(), wheelAngle, rootOf(poseStack));
        this.element = created;
        this.proxy = Proxies.installExternal(this, created, template, identity, created::normalizedRoot);
        ContraptionBogey.HostFrame frame = created.hostFrame();
        this.proxy.publishVisualFrame(created.normalizedRoot(frame));
        ContraptionBogeyTickers.register(this);
        return created;
    }

    /** The proxy drawing this bogey, or {@code null} before the first update. */
    @Nullable
    ProxyInstance<ContraptionBogey> proxy() {
        return proxy;
    }

    /** Current world position of the bogey, for the tick entry; {@code null} before the first update. */
    @Nullable
    BlockPos worldPos() {
        ContraptionBogey bogey = element;
        if (bogey == null) {
            return null;
        }
        org.joml.Vector3f position = bogey.root().getPosition();
        return BlockPos.containing(position.x, position.y, position.z);
    }

    private Transform rootOf(PoseStack poseStack) {
        Vec3i origin = context.renderOrigin();
        return ContraptionBogeyProxy.rootOf(poseStack.last().pose(), new org.joml.Vector3i(origin.getX(), origin.getY(), origin.getZ()));
    }

    /**
     * Create hides a bogey while its carriage is inside a portal. Releasing is the honest answer:
     * the models would otherwise keep drawing through the portal cut-out, and the next
     * {@link #update} rebuilds everything from the same registered model group.
     */
    @Override
    public void hide() {
        releaseInstance();
    }

    @Override
    public void updateLight(int packedLight) {
        this.light = packedLight;
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        // Our models are not Flywheel instances; the contraption's own crumbling effect does not
        // apply to them.
    }

    @Override
    public void delete() {
        releaseInstance();
    }

    private void releaseInstance() {
        ContraptionBogeyTickers.unregister(this);
        ProxyInstance<ContraptionBogey> instance = proxy;
        this.proxy = null;
        this.element = null;
        if (instance != null) {
            instance.release(Minecraft.getInstance().level);
        }
    }

    /** Packed light the bogey was last lit with; kept for diagnostics. */
    public int light() {
        return light;
    }

    /**
     * Empty {@link BogeyRenderer} for the non-Flywheel fallback path.
     *
     * <p>Register it next to {@link ProxyBogeyVisual} so Create does not draw its own model over
     * ours. Without Flywheel nothing is drawn at all — acceptable, since that path only triggers
     * when visualization is unsupported.
     */
    public static final class NoopRenderer implements BogeyRenderer {

        @Override
        public void render(CompoundTag bogeyData, float wheelAngle, float partialTick, PoseStack poseStack,
                           net.minecraft.client.renderer.MultiBufferSource bufferSource,
                           int packedLight, int packedOverlay, boolean inContraption) {
            // Intentionally empty: the proxy draws this bogey.
        }
    }
}
