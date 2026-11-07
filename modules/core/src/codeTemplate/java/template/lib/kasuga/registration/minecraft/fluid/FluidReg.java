package template.lib.kasuga.registration.minecraft.fluid;

import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import lib.kasuga.registration.minecraft.fluid.FluidBlockReg;
import lib.kasuga.registration.stages.RegisterStageContext;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.registries.Registries;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Managed fluid registration: a {@link FluidType} plus the source and flowing {@link Fluid}
 * instances, registered through the 2.0 pipeline.
 * <p>
 * The three objects live in two registries, and {@code RegisterEvent} fires once per registry in
 * no defined order, so nothing here resolves a registry entry while a registration event is
 * still running. Every cross-registry reference is a {@link Supplier} over a
 * {@link DeferredHolder}, dereferenced on first use, after registry freeze.
 * <p>
 * This is the 2.0 counterpart of the 1.0 {@code FluidReg}, on NeoForge 1.21.1 where
 * {@code ForgeFlowingFluid} was replaced by {@link BaseFlowingFluid}.
 * <p>
 * <b>The liquid block is deliberately not part of this registration.</b> {@code LiquidBlock}
 * resolves {@code getSource(false)} inside its constructor, which cannot be deferred past the
 * BLOCK registration event while the FLUID event may not have run yet. Fluid blocks are tracked
 * separately; {@link #getSourceSupplier()} and {@link #getSourceHolder()} expose what they need.
 *
 * @param <S> the source fluid class.
 * @param <F> the flowing fluid class.
 */
@CodeTemplate(generator = "Reg")
public class FluidReg<S extends BaseFlowingFluid.Source, F extends BaseFlowingFluid.Flowing> extends Reg<FluidReg<S, F>, FluidType> {

    public static final String DEFAULT_FLOWING_SUFFIX = "_flowing";

    private final String name;
    private final String flowingName;
    private final FluidTypeReg<?> type;

    private DeferredHolder<Fluid, Fluid> sourceHolder;
    private DeferredHolder<Fluid, Fluid> flowingHolder;

    private Function<BaseFlowingFluid.Properties, S> sourceFactory;
    private Function<BaseFlowingFluid.Properties, F> flowingFactory;

    private ResourceLocation sourceId;
    private ResourceLocation flowingId;

    private int slopeFindDistance = 4;
    private int levelDecreasePerBlock = 1;
    private int tickRate = 5;
    private float explosionResistance = 100.0F;

    public FluidReg(String name) {
        this(name, name + DEFAULT_FLOWING_SUFFIX);
    }

    public FluidReg(String name, String flowingName) {
        this.name = name;
        this.flowingName = flowingName;
        this.type = FluidTypeReg.of(name);
        this.addChild(this.type);
    }

    /* ---------------------------------------------------------------- fluid instances */

    /**
     * @param sourceFactory  builds the source fluid from {@link BaseFlowingFluid.Properties}.
     * @param flowingFactory builds the flowing fluid from {@link BaseFlowingFluid.Properties}.
     */
    public FluidReg<S, F> instances(Function<BaseFlowingFluid.Properties, S> sourceFactory,
                                    Function<BaseFlowingFluid.Properties, F> flowingFactory) {
        this.sourceFactory = sourceFactory;
        this.flowingFactory = flowingFactory;
        return this;
    }

    public FluidReg<S, F> slopeFindDistance(int distance) {
        this.slopeFindDistance = distance;
        return this;
    }

    public FluidReg<S, F> levelDecreasePerBlock(int decrease) {
        this.levelDecreasePerBlock = decrease;
        return this;
    }

    public FluidReg<S, F> tickRate(int tickRate) {
        this.tickRate = tickRate;
        return this;
    }

    public FluidReg<S, F> explosionResistance(float resistance) {
        this.explosionResistance = resistance;
        return this;
    }

    public FluidReg<S, F> numericProperties(int levelDecrease, int slopeDistance, int tickRate, float explosionResistance) {
        return levelDecreasePerBlock(levelDecrease)
                .slopeFindDistance(slopeDistance)
                .tickRate(tickRate)
                .explosionResistance(explosionResistance);
    }

    /* ---------------------------------------------------------------- fluid type */

    /**
     * Configures the underlying {@link FluidTypeReg}: density, viscosity, light level, sounds and
     * the client sprites.
     * <p>
     * This is the single entry point for {@code FluidType.Properties} so there is exactly one place
     * that mutates them, instead of the two overlapping channels the 1.0 API exposed.
     *
     * <pre>{@code
     * FluidReg.create("crude_oil")
     *     .instances(BaseFlowingFluid.Source::new, BaseFlowingFluid.Flowing::new)
     *     .numericProperties(2, 3, 10, 100f)
     *     .type(t -> t.viscosity(3000).density(1400).defaultBucketSounds()
     *                 .still("block/crude_oil_still").flowing("block/crude_oil_flow"));
     * }</pre>
     *
     * @param configurer receives this registration's fluid type registration.
     */
    public FluidReg<S, F> type(Function<FluidTypeReg<?>, FluidTypeReg<?>> configurer) {
        configurer.apply(this.type);
        return this;
    }

    /** @return the source fluid id, resolved lazily against the parent registry namespace. */
    public ResourceLocation typeId() {
        return this.type.getResourceLocation();
    }

    public FluidTypeReg<?> getFluidTypeReg() {
        return this.type;
    }

    /**
     * Attaches a liquid block to this fluid.
     * <p>
     * The block is registered into {@code minecraft:block}, which NeoForge dispatches after
     * {@code minecraft:fluid}, so {@link #getSource()} is already bound when the block is
     * constructed. See {@link FluidBlockReg} for that contract.
     *
     * @param block the block registration to attach.
     */
    public FluidReg<S, F> block(FluidBlockReg<?> block) {
        this.addChild(block);
        return this;
    }

    /**
     * @return the registered source fluid. Only call this once the FLUID registry has been
     *         dispatched; inside a BLOCK registration event it is already bound.
     */
    public FlowingFluid getSource() {
        return (FlowingFluid) sourceHolder.get();
    }

    public FlowingFluid getFlowing() {
        return (FlowingFluid) flowingHolder.get();
    }

    /* ---------------------------------------------------------------- registration */

    @Override
    public void register(RegisterContext<?> context) {
        castStage(context, ctx -> {
            if (ctx.getRegistryKey() == net.minecraft.core.registries.Registries.FLUID) {
                registerFluids(ctx);
            }
        });
    }

    private void registerFluids(RegisterStageContext context) {
        ResourceLocation sid = applyProperties(ResourceLocation.class,
                ResourceLocation.fromNamespaceAndPath("minecraft", name));
        ResourceLocation fid = applyProperties(ResourceLocation.class,
                ResourceLocation.fromNamespaceAndPath("minecraft", flowingName));
        this.sourceId = sid;
        this.flowingId = fid;

        this.sourceHolder = DeferredHolder.create(net.minecraft.core.registries.Registries.FLUID, sid);
        this.flowingHolder = DeferredHolder.create(net.minecraft.core.registries.Registries.FLUID, fid);

        {
            RegisterStageContext stage = context;
            // Each supplier only derefs a deferred holder, so constructing the properties is safe
            // while the FLUID registration event is running.
            Supplier<FluidType> typeSupplier = this::getFluidType;
            Supplier<Fluid> sourceSupplier = () -> sourceHolder.get();
            Supplier<Fluid> flowingSupplier = () -> flowingHolder.get();

            BaseFlowingFluid.Properties fluidProperties = new BaseFlowingFluid.Properties(
                    typeSupplier,
                    sourceSupplier,
                    flowingSupplier
            );
            fluidProperties
                    .levelDecreasePerBlock(levelDecreasePerBlock)
                    .slopeFindDistance(slopeFindDistance)
                    .tickRate(tickRate)
                    .explosionResistance(explosionResistance);

            stage.register(net.minecraft.core.registries.Registries.FLUID, sid, () -> sourceFactory.apply(fluidProperties));
            stage.register(net.minecraft.core.registries.Registries.FLUID, fid, () -> flowingFactory.apply(fluidProperties));
        }
    }

    @SuppressWarnings("unchecked")
    private static void castStage(RegisterContext<?> context, Consumer<RegisterStageContext> consumer) {
        ((RegisterContext<RegisterStageContext>) (RegisterContext<?>) context)
                .onStage(RegistrationStage.REGISTER_EVENT, consumer);
    }

    /* ---------------------------------------------------------------- accessors */

    public Holder<FluidType> getFluidTypeHolder() {
        return type.getHolder();
    }

    public FluidType getFluidType() {
        return type.getEntry();
    }

    public Holder<Fluid> getSourceHolder() {
        return sourceHolder;
    }

    public Holder<Fluid> getFlowingHolder() {
        return flowingHolder;
    }

    public Holder<Fluid> getHolder() {
        return sourceHolder;
    }

    /**
     * @return the source fluid, lazily. Safe to hand to code that runs after registry freeze,
     *         for example a liquid block created in a later commit.
     */
    public Supplier<FlowingFluid> getSourceSupplier() {
        return () -> (FlowingFluid) sourceHolder.get();
    }

    public Supplier<FlowingFluid> getFlowingSupplier() {
        return () -> (FlowingFluid) flowingHolder.get();
    }

    public String getName() {
        return name;
    }

    public String getFlowingName() {
        return flowingName;
    }

    public ResourceLocation getSourceId() {
        return sourceId;
    }

    public ResourceLocation getFlowingId() {
        return flowingId;
    }

    @Override
    public FluidType getEntry() {
        return getFluidType();
    }
}
