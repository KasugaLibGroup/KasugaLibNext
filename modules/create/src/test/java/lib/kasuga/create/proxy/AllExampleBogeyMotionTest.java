package lib.kasuga.create.proxy;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;
import test.kasuga.create.bogey.AllExampleBogey;
import test.kasuga.create.bogey.ExampleBogeyBlockEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AllExampleBogeyMotionTest {

    private static final ResourceLocation WHEEL = ResourceLocation.fromNamespaceAndPath(
            "kasuga_lib", "models/block/bogey/pk209p/pk209p_wheel.obj");

    @Test
    void placedBogeyUsesTickMotionAndItsAngleHasNoPartialTickParameter() {
        Function<ExampleBogeyBlockEntity, Float> blockAngle = ExampleBogeyBlockEntity::wheelAngle;
        assertNotNull(blockAngle);

        ModelPart tickWheel = AllExampleBogey.spinningWheelsForTick(
                List.of(ModelPart.of(WHEEL)), () -> 7f).getFirst();
        assertEquals(ModelPart.TimeDomain.TICK, tickWheel.motion().timeDomain());
    }

    @Test
    void contraptionBogeyUsesCreateFrameMotion() {
        ModelPart frameWheel = AllExampleBogey.spinningWheelsForFrame(
                List.of(ModelPart.of(WHEEL)), () -> 7f).getFirst();
        assertEquals(ModelPart.TimeDomain.FRAME, frameWheel.motion().timeDomain());
    }

    @Test
    void placedBogeyReadsCreateLatestVirtualAngleRatherThanPreviousTick() throws Exception {
        ExampleBogeyBlockEntity entity = new ExampleBogeyBlockEntity(
                BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState(),
                () -> null);

        Field field = AbstractBogeyBlockEntity.class.getDeclaredField("virtualAnimation");
        field.setAccessible(true);
        LerpedFloat animation = (LerpedFloat) field.get(entity);
        animation.startWithValue(350f);
        animation.setValue(2f);

        assertEquals(2f, Mth.wrapDegrees(entity.wheelAngle()), 1e-5f);
    }
}
