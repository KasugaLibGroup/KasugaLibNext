package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Default {@link ElementProxy} for block-entity hosts: a static "machine + model" descriptor.
 *
 * <p>The type parameter is the <em>element</em> — the block entity that implements
 * {@link ProxyHost} — not the block. A block entity is created per position, so one
 * {@code BlockProxy} instance may serve any number of blocks and block entity types
 * that share the same machine/model; anything that varies per element must be read from
 * {@code element} (block state, NBT fields, constructor arguments) inside
 * {@link #configure(BlockEntity)}, or supplied by overriding {@link #pull} / {@link #push}.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *     <li>{@link #configure(BlockEntity)} is called exactly once per host instance, on the
 *     first game tick after the block entity has been constructed, loaded from NBT and had
 *     its level attached. It must not be called from the host constructor.</li>
 *     <li>{@link #pull} and {@link #push} run every game tick, only when the state machine has
 *     been built successfully. They never run on a host whose {@link ProxyConfig#machine()}
 *     is {@code null}.</li>
 *     <li>{@link #onDetach} runs once on release (chunk unload, removal, explicit release).</li>
 * </ul>
 *
 * <h2>Extending</h2>
 * <p>Content with per-element configuration subclasses this type and overrides
 * {@link #configure(BlockEntity)}, {@link #pull} or {@link #push}:
 *
 * <pre>{@code
 * final class DoorProxy extends BlockProxy<DoorBlockEntity> {
 *     DoorProxy() { super(MyMachines.DOOR, MyModels.DOOR); }
 *
 *     @Override
 *     public ProxyConfig configure(DoorBlockEntity be) {
 *         return be.isSliding() ? config() : SLIDING_CONFIG;
 *     }
 *
 *     @Override
 *     public void pull(DoorBlockEntity be, StateMachine<?> machine) {
 *         machine.setInternal(DoorParams.OPEN, be.getBlockState().getValue(DoorBlock.OPEN));
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are immutable and may be shared between hosts; a subclass that keeps mutable
 * state must not assume it is bound to a single block entity.
 *
 * @param <T> the block entity type this proxy configures
 */
public class BlockProxy<T extends BlockEntity> implements ElementProxy<T> {

    private final ProxyConfig config;

    /**
     * A proxy that only attaches a state machine, without a client model. Useful for logic-only
     * hosts (invisible machines, data relays) that are driven entirely by {@link #pull}.
     *
     * @param machine the state machine definition to build for each host
     * @param model   the model to bind; {@code null} for a logic-only host
     */
    public BlockProxy(@Nullable Id machine, @Nullable ResourceLocation model) {
        this(new ProxyConfig(machine, model, null, null));
    }

    /**
     * A proxy with a model and an MMD model name.
     *
     * @param machine   the state machine definition, or {@code null} for a model-only host
     * @param model     the model to bind, or {@code null} for a logic-only host
     * @param modelName MMD-specific archive entry name; {@code null} for non-MMD models
     */
    public BlockProxy(@Nullable Id machine, @Nullable ResourceLocation model, @Nullable String modelName) {
        this(new ProxyConfig(machine, model, modelName, null));
    }

    /**
     * Full form.
     *
     * @param machine       the state machine definition, or {@code null}
     * @param model         the model to bind, or {@code null}
     * @param modelName     MMD-specific archive entry name, or {@code null}
     * @param rootTransform supplier of the root pose in world space; {@code null} means the
     *                      block centre ({@code x+0.5, y, z}). Evaluated on the client, lazily,
     *                      every time the model is (re)bound.
     */
    public BlockProxy(@Nullable Id machine, @Nullable ResourceLocation model, @Nullable String modelName,
                      @Nullable Supplier<Transform> rootTransform) {
        this(new ProxyConfig(machine, model, modelName, rootTransform));
    }

    /**
     * Subclass form: takes the already-built configuration.
     *
     * @param config the configuration this proxy reports; must not be {@code null}
     */
    protected BlockProxy(ProxyConfig config) {
        this.config = config;
    }

    /**
     * The configuration this proxy was built with. Subclasses use it as the base case of a
     * per-element decision, as in the class example.
     */
    public ProxyConfig config() {
        return config;
    }

    /**
     * Returns the fixed configuration. Override to vary it per element; the result is evaluated
     * once, so anything read here must be stable for the lifetime of the host (a block state that
     * changes at runtime must be mapped in {@link #pull} instead).
     *
     * <p>Returning {@code null} disables the host: the proxy instance logs the refusal once and
     * never ticks again.
     */
    @Override
    public ProxyConfig configure(T element) {
        return config;
    }

    /**
     * Maps element state to machine parameters before the machine advances.
     *
     * <p>Runs on both sides, once per game tick, after the vanilla ticker (if the block combines
     * one via {@code ProxyTickers.forBlockEntity(type, existing)}), so the values observed here are
     * the ones the machine will act on this tick. Default: no-op.
     */
    @Override
    public void pull(T element, StateMachine<?> machine) {
    }

    /**
     * Writes machine parameters back to the element after the machine advanced.
     *
     * <p>Runs on both sides, once per game tick, after transitions have fired, so the values are
     * the post-transition ones. Writing level state here is only authoritative on the server; gate
     * client-side writes on {@code element.getLevel().isClientSide()} when they would conflict.
     * Default: no-op.
     */
    @Override
    public void push(T element, StateMachine<?> machine) {
    }

    /**
     * Releases anything this proxy acquired for the element. Called once, after the model has been
     * unbound and the machine torn down. Default: no-op.
     */
    @Override
    public void onDetach(T element) {
    }
}
