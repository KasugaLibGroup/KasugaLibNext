package lib.kasuga.create.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.StructureTransform;
import lib.kasuga.rendering.models.mc.proxies.ProxyModelBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyDefinition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyKey;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContraptionProxyStateTest {
    private static final BlockPos LOCAL = new BlockPos(2, 1, -3);

    @Test
    void synchronizedContraptionStateReachesTheExistingProxySessionOnItsNextLogicTick() throws Exception {
        var level = TestLevel.create();
        var contraption = new TestContraption();
        var entity = new TestEntity(level, contraption);
        BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, false);
        BlockState open = closed.setValue(BlockStateProperties.OPEN, true);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("preserved", "captured_data");
        contraption.getBlocks().put(LOCAL, new StructureBlockInfo(LOCAL, closed, nbt));
        var model = new CapturedModel(closed);
        model.setLevel(level);
        contraption.presentBlockEntities.put(LOCAL, model);
        model.bindVisualHost(ContraptionProxyModels.instanceIdentity(entity.getUUID(), LOCAL),
                ContraptionProxyModels.anchorTransform(new lib.kasuga.scene.ScenePose(
                        Vec3.ZERO, new org.joml.Matrix4f()), LOCAL));
        List<BlockState> inputs = new ArrayList<>();
        var runtime = new ProxyRuntime();
        var definition = new ProxyDefinition<CapturedModel, BlockState, Void, Void, Void>(
                ProxyKey.blockEntity("test", "captured_state"), ignored -> ProxyComposition.of(
                LogicalStepPolicy.fixed(0.05, 1), captured -> {
                    inputs.add(captured.getBlockState());
                    return captured.getBlockState();
                }, ProxyControllerPort.none(), (captured, output) -> {},
                ProxyCommandPort.none(), ProxyVisualPort.none(), List.of()));
        runtime.register(definition);
        var session = runtime.attach(definition, model, model.proxyAttachToken()).orElseThrow();
        model.proxySession(session);
        session.activate();

        // Exercise the adapter's real attachment tick, including host binding and ProxyTickers.
        Class<?> attachmentType = Class.forName(ContraptionProxyModels.class.getName() + "$Attachment");
        var constructor = attachmentType.getDeclaredConstructor(
                AbstractContraptionEntity.class, Contraption.class);
        constructor.setAccessible(true);
        Object attachment = constructor.newInstance(entity, contraption);
        // FML's slim server bootstrap strips Create's client pose method before disabling dist-cleaning.
        // Supply that existing SceneHost port while exercising the real attachment/ticker/state path.
        var hostField = attachmentType.getDeclaredField("host");
        hostField.setAccessible(true);
        hostField.set(attachment, new lib.kasuga.scene.SceneHost() {
            @Override public boolean isAlive() { return true; }
            @Override public lib.kasuga.scene.ScenePose pose(float partialTick) {
                return new lib.kasuga.scene.ScenePose(Vec3.ZERO, new org.joml.Matrix4f());
            }
        });
        Method tick = attachmentType.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(attachment);
        assertSame(closed, inputs.getLast());
        long revision = session.revision();

        // Create's client packet replaces StructureBlockInfo while leaving captured BEs untouched.
        // Its client-only handler is stripped by the slim FML server bootstrap.
        contraption.getBlocks().put(LOCAL, new StructureBlockInfo(LOCAL, open, nbt));
        assertSame(open, contraption.getBlocks().get(LOCAL).state());
        assertSame(closed, model.getBlockState(), "Create's packet changes the map, not the captured BE");
        level.gameTime++;
        tick.invoke(attachment);

        assertSame(open, inputs.getLast(), "The original session must observe the synchronized OPEN");
        assertSame(open, model.getBlockState());
        assertSame(session, model.proxySession());
        assertTrue(session.revision() > revision);
        assertEquals(LOCAL, model.getBlockPos());
        assertSame(nbt, contraption.getBlocks().get(LOCAL).nbt());
        session.close();
    }

    private static final class CapturedModel extends ProxyModelBlockEntity {
        CapturedModel(BlockState state) {
            super(null, LOCAL, state, ResourceLocation.parse("test:models/door.bbmodel"),
                    null, 0f, Map.of());
        }

        @Override
        public boolean isValidBlockState(BlockState state) {
            return true;
        }
    }

    private static final class TestLevel extends ClientLevel {
        private long gameTime = 1;

        private TestLevel() {
            super(null, null, null, null, 0, 0, null, null, false, 0);
        }

        static TestLevel create() throws Exception {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            TestLevel result = (TestLevel) ((Unsafe) field.get(null)).allocateInstance(TestLevel.class);
            var side = Level.class.getDeclaredField("isClientSide");
            side.setAccessible(true);
            side.setBoolean(result, true);
            result.gameTime = 1;
            return result;
        }

        @Override
        public long getGameTime() {
            return gameTime;
        }
    }

    private static final class TestContraption extends Contraption {
        private int colliderInvalidations;

        @Override public boolean assemble(Level level, BlockPos pos) { return false; }
        @Override public boolean canBeStabilized(Direction facing, BlockPos pos) { return false; }
        @Override public ContraptionType getType() { return null; }
        @Override public void invalidateColliders() { colliderInvalidations++; }
    }

    private static final class TestEntity extends AbstractContraptionEntity {
        private final Level testLevel;

        TestEntity(Level level, Contraption captured) {
            super(EntityType.MARKER, null);
            testLevel = level;
            contraption = captured;
            captured.entity = this;
        }

        @Override public Level level() { return testLevel; }
        @Override public void applyLocalTransforms(PoseStack matrices, float partialTick) {}
        @Override protected void tickContraption() {}
        @Override protected StructureTransform makeStructureTransform() { return null; }
        @Override public ContraptionRotationState getRotationState() { return new ContraptionRotationState(); }
        @Override public Vec3 applyRotation(Vec3 local, float partialTick) { return local; }
        @Override public Vec3 reverseRotation(Vec3 local, float partialTick) { return local; }
        @Override protected float getStalledAngle() { return 0; }
        @Override protected void handleStallInformation(double x, double y, double z, float angle) {}
    }
}
