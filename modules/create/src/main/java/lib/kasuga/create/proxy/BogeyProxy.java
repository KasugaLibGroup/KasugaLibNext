package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Default {@link ElementProxy} for Create bogey block entities.
 *
 * <p>It translates the two things Create knows — the {@link com.simibubi.create.content.trains.bogey.BogeyStyle}
 * stored in the block entity's NBT and the size declared by the block — into the three things the
 * model pipeline needs: a state machine, a model and a root transform.
 *
 * <h2>Model resolution</h2>
 * <p>The model is looked up in {@link BogeyModels} by style and size; when nothing is registered,
 * {@code fallbackModel} (constructor argument) is used. Returning {@code null} from
 * {@link #configure} disables the proxy with a single warning instead of retrying every tick.
 *
 * <h2>Extending</h2>
 * <p>Content subclasses this to exchange data with the machine, for example:
 *
 * <pre>{@code
 * new BogeyProxy<MyBogeyBlockEntity>(MyMachines.BOGEY, null) {
 *     @Override
 *     public void pull(MyBogeyBlockEntity element, StateMachine<?> machine) {
 *         machine.setInternal(MyParams.SPEED, element.getVirtualAngle(1f));
 *     }
 * };
 * }</pre>
 *
 * <p>Keep everything else in this class: the placement policy lives in {@link BogeyRoots} and the
 * style → model policy lives in {@link BogeyModels}, so per-content subclasses stay small.
 *
 * @param <T> the concrete block entity type; must be a Create bogey block entity
 */
public class BogeyProxy<T extends AbstractBogeyBlockEntity> implements ElementProxy<T> {

    private final @Nullable Id machine;
    private final @Nullable ResourceLocation fallbackModel;

    /**
     * @param machine       the state machine definition, or {@code null} for a model-only proxy
     * @param fallbackModel the model used when {@link BogeyModels} has no entry for the style, or
     *                      {@code null} to refuse unregistered styles
     */
    public BogeyProxy(@Nullable Id machine, @Nullable ResourceLocation fallbackModel) {
        this.machine = machine;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public @Nullable ProxyConfig configure(T element) {
        BogeyModels.Placement placement = BogeyModels.resolve(element.getStyle(), sizeOf(element));
        List<ModelPart> parts = partsFor(element, placement);
        float yOffset = placement != null ? placement.yOffset() : BogeyRoots.CREATE_Y_OFFSET;
        if (machine == null && parts.isEmpty()) {
            return null;
        }
        // The model always travels as parts, never as config.model(): with a machine present that
        // field would give the machine its own binding of the same geometry (a second pipeline
        // instance, a second draw). One shape, one binding path.
        return new ProxyConfig(machine, null, null, () -> BogeyRoots.of(element, yOffset), parts);
    }

    /**
     * Parts to draw for one element — the hook content uses to bind per-element data onto the
     * registered model group.
     *
     * <p>The registry entry is element-agnostic (model ids, offsets), but a spin needs the host:
     * a bogey's wheel angle lives on the block entity. Override this to return the registered parts
     * with their {@link ModelPart.Spin} filled in, e.g.
     *
     * <pre>{@code
     * protected List<ModelPart> partsFor(MyBogeyBlockEntity element, BogeyModels.Placement placement) {
     *     return super.partsFor(element, placement).stream()
     *             .map(part -> isWheel(part) ? part.spinning(ModelPart.Spin.tick(() -> element.getVirtualAngle(1f))) : part)
     *             .toList();
     * }
     * }</pre>
     *
     * <p>Keep the model group itself (which parts, where) in {@link BogeyModels} so every style shares
     * one layout description; only the data binding belongs here.
     *
     * @param element   the block entity being configured
     * @param placement the registered model group, or {@code null} when neither the style nor the
     *                  fallback model resolved
     * @return the parts to bind, in draw order; empty disables the model (machine-only proxy)
     */
    protected List<ModelPart> partsFor(T element, @Nullable BogeyModels.Placement placement) {
        if (placement != null) {
            return placement.parts();
        }
        return fallbackModel != null ? List.of(ModelPart.of(fallbackModel)) : List.of();
    }

    /**
     * Reads the size from the block, because the block entity does not expose one.
     *
     * @return the size, or {@code null} when the block is not a Create bogey block
     */
    public static @Nullable BogeySizes.BogeySize sizeOf(AbstractBogeyBlockEntity element) {
        return element.getBlockState().getBlock() instanceof AbstractBogeyBlock<?> block
                ? block.getSize()
                : null;
    }
}
