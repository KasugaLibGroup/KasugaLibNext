package template.lib.kasuga.registration.minecraft.fluid;

import com.mojang.logging.LogUtils;
import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.registration.core.ChildrenLookup;
import lib.kasuga.registration.minecraft.fluid.FluidReg;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;
import org.slf4j.Logger;

import java.util.function.Function;

/**
 * The liquid block that holds a fluid registered by {@link FluidReg}.
 * <p>
 * <b>Registration order contract.</b> {@link LiquidBlock} resolves {@code getSource(false)} inside
 * its constructor and caches the resulting states in a private final list, so a liquid block
 * cannot exist before its fluid does. NeoForge fires {@code RegisterEvent} once per registry in a
 * fixed order built by {@code GameData.getRegistrationOrder()}: the three special-case registries,
 * then the vanilla registries in declaration order, then modded registries sorted by namespace.
 * In {@code BuiltInRegistries} the fluid registry is declared before the block registry, so
 * {@code minecraft:fluid} is always dispatched before {@code minecraft:block}, and the source fluid
 * is bound by the time this block is constructed.
 * <p>
 * That order comes from NeoForge rather than from a documented guarantee, so
 * {@link #getFluid()} fails with an explicit message instead of a bare
 * {@code IllegalStateException} if it ever stops holding.
 *
 * @param <T> the liquid block class.
 */
@CodeTemplate(generator = "Reg")
@RegGenerator(
        modifiers = {
                @RegGenerator.Modifier(
                        type = "BlockProperties",
                        target = BlockBehaviour.Properties.class,
                        enumeration = {
                                "mapColor",
                                "noCollission",
                                "noOcclusion",
                                "friction",
                                "speedFactor",
                                "jumpFactor",
                                "sound",
                                "lightLevel",
                                "strength",
                                "randomTicks",
                                "noLootTable",
                                "dropsLike",
                                "lootFrom",
                                "ignitedByLava",
                                "liquid",
                                "forceSolidOn",
                                "pushReaction",
                                "replaceable"
                        }
                )
        }
)
public class FluidBlockReg<T extends LiquidBlock> extends MinecraftDeferRegistryReg<FluidBlockReg<T>, Block, T> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Function<FluidBlockReg<T>, Function<FlowingFluid, Function<BlockBehaviour.Properties, T>>> supplier;

    public static <T extends LiquidBlock> FluidBlockReg<T> of(String name,
                                                              Function<FlowingFluid, Function<BlockBehaviour.Properties, T>> builder) {
        return new FluidBlockReg<>(name, reg -> builder);
    }

    /**
     * @param name     the block registration name, relative to the {@link FluidReg} namespace.
     * @param supplier builds the block from the resolved source fluid and its properties.
     */
    public FluidBlockReg(String name, Function<FluidBlockReg<T>, Function<FlowingFluid, Function<BlockBehaviour.Properties, T>>> supplier) {
        super(name, Registries.BLOCK);
        this.supplier = supplier;
    }

    @Override
    protected T createObject(ResourceLocation id) {
        BlockBehaviour.Properties properties = this.transform(FluidBlockRegModifiers.TYPE_BLOCK_PROPERTIES, BlockBehaviour.Properties.of());
        properties = this.applyProperties(BlockBehaviour.Properties.class, properties);
        return supplier.apply(this).apply(getFluid()).apply(properties);
    }

    /**
     * Resolves the source fluid from the owning {@link FluidReg}.
     *
     * @throws IllegalStateException if this block is not attached to a {@link FluidReg}, or if the
     *         fluid registry has not been dispatched yet.
     */
    protected FlowingFluid getFluid() {
        FluidReg<?, ?> fluidReg = getFluidReg();
        if (fluidReg == null) {
            throw new IllegalStateException("Fluid block \"" + getName() + "\" is not attached to a FluidReg; attach it with FluidReg#block(...)");
        }
        try {
            return fluidReg.getSource();
        } catch (IllegalStateException e) {
            LOGGER.error("Fluid block \"{}\" was constructed before its fluid \"{}\" was registered.", getName(), fluidReg.getSourceName());
            throw new IllegalStateException(
                    "Fluid block \"" + getName() + "\" needs the source fluid of \"" + fluidReg.getSourceName()
                            + "\" to exist already, but the FLUID registry has not been dispatched. "
                            + "NeoForge dispatches minecraft:fluid before minecraft:block; if that order changed, "
                            + "fluid blocks can no longer be registered through RegisterEvent.",
                    e
            );
        }
    }

    /**
     * @return the owning {@link FluidReg}, or {@code null} when this registration is not a child of one.
     */
    public FluidReg<?, ?> getFluidReg() {
        return ChildrenLookup.findParent(this, FluidReg.class, (reg, type) -> type.isInstance(reg) ? reg : null);
    }
}
