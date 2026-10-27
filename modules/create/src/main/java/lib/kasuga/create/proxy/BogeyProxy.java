package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

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
 *         machine.setInternal(MyParams.SPEED, element.getVirtualAngle(0f));
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
        ResourceLocation model = placement != null ? placement.model() : fallbackModel;
        float yOffset = placement != null ? placement.yOffset() : BogeyRoots.CREATE_Y_OFFSET;
        if (machine == null && model == null) {
            return null;
        }
        return new ProxyConfig(machine, model, null, () -> BogeyRoots.of(element, yOffset));
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
