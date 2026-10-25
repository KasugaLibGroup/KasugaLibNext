package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Default {@link ElementProxy} for item hosts: a static "model (+ optional machine)" descriptor.
 *
 * <p>The element type is {@link ItemStack}: items are registry singletons, so the only thing that
 * can identify an <em>instance</em> is the stack, and the host is whatever drives that stack (an
 * {@code ItemStack} in a hand, a block entity acting as a display, an item renderer). Keeping the
 * stack as the element type lets a single proxy decide per stack — count, damage, NBT, components —
 * without guessing from the item alone.
 *
 * <h2>What items cannot do</h2>
 * <p>Items have no tick channel: they are not block entities and they are not entities, so nothing
 * in the game advances them. Consequently:
 * <ul>
 *     <li>{@link #configure(ItemStack)} still runs once, on the first tick of the host that drives
 *     the stack (for example a display block entity), and defines the model/machine to attach.</li>
 *     <li>{@link #pull} and {@link #push} only run if the host actually ticks the proxy. A proxy
 *     attached to a plain stack with no driving host is configuration-only and its hooks never
 *     fire.</li>
 *     <li>Item rendering is driven by the renderer that owns the stack; this proxy never binds a
 *     model by itself on the server, because there is no server-side instance to bind.</li>
 * </ul>
 *
 * <h2>Extending</h2>
 * <p>Subclass to vary the configuration per stack:
 *
 * <pre>{@code
 * final class BatteryProxy extends ItemProxy {
 *     BatteryProxy() { super(MyMachines.BATTERY, MyModels.BATTERY); }
 *
 *     @Override
 *     public ProxyConfig configure(ItemStack stack) {
 *         return stack.getDamageValue() == 0 ? config() : EMPTY_CONFIG;
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are immutable and may be shared between stacks; a subclass that keeps mutable
 * state must key it by the stack, not by the proxy instance.
 */
public class ItemProxy implements ElementProxy<ItemStack> {

    private final ProxyConfig config;

    /**
     * A proxy that attaches a state machine or a model without a name.
     *
     * @param machine the state machine definition, or {@code null} for a model-only proxy
     * @param model   the model to bind, or {@code null} for a logic-only proxy
     */
    public ItemProxy(@Nullable Id machine, @Nullable ResourceLocation model) {
        this(new ProxyConfig(machine, model, null, null));
    }

    /**
     * A proxy with an MMD model name.
     *
     * @param machine   the state machine definition, or {@code null}
     * @param model     the model to bind, or {@code null}
     * @param modelName MMD-specific archive entry name; {@code null} for non-MMD models
     */
    public ItemProxy(@Nullable Id machine, @Nullable ResourceLocation model, @Nullable String modelName) {
        this(new ProxyConfig(machine, model, modelName, null));
    }

    /**
     * Full form.
     *
     * @param machine       the state machine definition, or {@code null}
     * @param model         the model to bind, or {@code null}
     * @param modelName     MMD-specific archive entry name, or {@code null}
     * @param rootTransform supplier of the root pose in the coordinate space of the host that
     *                      drives the stack (hand space, display space, ...); {@code null} leaves
     *                      the choice to the host
     */
    public ItemProxy(@Nullable Id machine, @Nullable ResourceLocation model, @Nullable String modelName,
                     @Nullable Supplier<Transform> rootTransform) {
        this(new ProxyConfig(machine, model, modelName, rootTransform));
    }

    /**
     * Subclass form: takes the already-built configuration.
     *
     * @param config the configuration this proxy reports; must not be {@code null}
     */
    protected ItemProxy(ProxyConfig config) {
        this.config = config;
    }

    /**
     * The configuration this proxy was built with. Subclasses use it as the base case of a
     * per-stack decision, as in the class example.
     */
    public ProxyConfig config() {
        return config;
    }

    /**
     * Returns the fixed configuration. Override to vary it per stack (damage, count, components).
     * Called once per proxy instance, before the first tick of the driving host.
     *
     * <p>Returning {@code null} disables the host: the proxy instance logs the refusal once and
     * never ticks again.
     */
    @Override
    public ProxyConfig configure(ItemStack element) {
        return config;
    }

    /**
     * Maps stack state to machine parameters. Only called when a host ticks this proxy; a stack
     * that nothing drives has no tick and this never runs.
     */
    @Override
    public void pull(ItemStack element, StateMachine<?> machine) {
    }

    /**
     * Writes machine parameters back to the stack. Only called when a host ticks this proxy; the
     * host decides on which side the write is authoritative.
     */
    @Override
    public void push(ItemStack element, StateMachine<?> machine) {
    }

    /**
     * Releases anything this proxy acquired for the stack. Called once on release. Default: no-op.
     */
    @Override
    public void onDetach(ItemStack element) {
    }
}
