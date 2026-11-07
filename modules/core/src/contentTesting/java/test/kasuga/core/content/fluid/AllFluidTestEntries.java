package test.kasuga.core.content.fluid;

import io.micronaut.context.annotation.Context;
import lib.kasuga.registration.minecraft.fluid.FluidBlockReg;
import lib.kasuga.registration.minecraft.fluid.FluidReg;
import lib.kasuga.registration.minecraft.item.ItemReg;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import test.kasuga.core.CoreTestApplication;

/**
 * Test content for the 2.0 fluid registration.
 * <p>
 * These entries exercise every part of {@link FluidReg} that has a runtime failure mode:
 * the fluid type, both fluid instances, the liquid block and the bucket. They are loaded
 * together with the mod through the contentTesting source set, so a dedicated server start
 * proves the same ordering contract the library documents.
 */
@Context()
public class AllFluidTestEntries {

    /**
     * The common case: one name for the type and the source, a derived flowing name, plus a
     * liquid block and a default bucket.
     */
    public static FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing> testFluid =
            new FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing>("test_fluid")
                    .instances(BaseFlowingFluid.Source::new, BaseFlowingFluid.Flowing::new)
                    .numericProperties(2, 4, 10, 100.0F)
                    .type(t -> t
                            .viscosity(1200)
                            .density(1400)
                            .lightLevel(1)
                            .defaultBucketSounds()
                            .tint(0xFF3FA7D6)
                            .still("block/texture")
                            .flowing("block/texture"))
                    .block(FluidBlockReg.of("test_fluid_block", fluid -> props -> new LiquidBlock(fluid, props)))
                    .bucket("test_fluid_bucket")
                    .setParent(CoreTestApplication.registry);

    /**
     * The migration shape: distinct type, source and flowing ids, and a custom bucket class.
     */
    public static FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing> testColaFluid =
            new FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing>(
                    "cola_fluid", "cola_still_fluid", "cola_flowing_fluid")
                    .instances(BaseFlowingFluid.Source::new, BaseFlowingFluid.Flowing::new)
                    .numericProperties(3, 3, 15, 100.0F)
                    .type(t -> t
                            .viscosity(2000)
                            .density(1100)
                            .defaultBucketSounds()
                            .tint(0xFF7A3B12)
                            .still("block/texture")
                            .flowing("block/texture"))
                    .block(FluidBlockReg.of("cola_fluid_block", fluid -> props -> new LiquidBlock(fluid, props)))
                    .bucket(ItemReg.of("cola_fluid_bucket", props -> new BucketItem(deferredColaSource(), props)))
                    .setParent(CoreTestApplication.registry);
    /**
     * The bucket factory runs during the ITEM registration event, which is after the FLUID
     * registry has been dispatched, so the source fluid is bound by then.
     */
    private static FlowingFluid deferredColaSource() {
        return testColaFluid.getSource();
    }}
