package test.kasuga.modelling.fsm;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * FSM 数据驱动链路的服务端冒烟测试（gameTestServer 环境）：
 *
 * <ol>
 *   <li>放 {@code kasuga_lib:fsm_test_block}（由 modelling contentTesting 的
 *       {@code kasuga_lib_content/fsm_blocks.json} 经 data-driven JSON 注册链注册）——机器惰性建机时必须拿到已注册的定义
 *       （FsmRegistries.GLOBAL 的定义桶，验证注册面），否则 machine() 为 null 且 warn；</li>
 *   <li>机器被 BE ticker 驱动（version 递增，idle(40t) → active 自动切换）；</li>
 *   <li>when_complete 双向循环（active(20t) → idle）继续运转；</li>
 *   <li>拆除方块后 BE 与机器被清理。</li>
 * </ol>
 *
 * <p>仅在服务端逻辑层验证（机器无 sink）；客户端渲染绑定（model bound / isRendering / 拆除摘除）
 * 由 {@code runClient} 手工冒烟验收（日志点已埋）。
 */
@GameTestHolder
public final class FsmBlockGameTest {

    private static final ResourceLocation FSM_BLOCK_ID = ResourceLocation.parse("kasuga_lib:fsm_test_block");

    private FsmBlockGameTest() {
    }

    private static Block fsmBlock() {
        Block block = BuiltInRegistries.BLOCK.get(FSM_BLOCK_ID);
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("kasuga_lib:fsm_test_block not registered — FsmTestRegistration missing");
        }
        return block;
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 300)
    public static void fsmBlockBuildsMachineAndTicks(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, fsmBlock());

        helper.startSequence()
                // BE 类型正确且机器已构建（定义必须已注册）
                .thenWaitUntil(() -> {
                    AnimationBlockEntity be = animationBlockEntity(helper, pos);
                    if (be.machine() == null) {
                        helper.fail("machine not built at " + pos + " — definition " + FsmTestRegistration.MACHINE_ID + " not registered?");
                    }
                })
                // 机器被 BE ticker 驱动：version 从 0 递增
                .thenWaitUntil(() -> {
                    AnimationBlockEntity be = animationBlockEntity(helper, pos);
                    if (be.machine().version() <= 0) {
                        helper.fail("machine not ticking at " + pos);
                    }
                })
                // idle (duration 40) 自动切换到 active（when_complete）
                .thenWaitUntil(() -> {
                    AnimationBlockEntity be = animationBlockEntity(helper, pos);
                    String state = be.machine().activeStates().get("base");
                    if (!"active".equals(state)) {
                        helper.fail("expected active after idle completes, got '" + state + "'");
                    }
                })
                // active (duration 20) 自动切回 idle —— 双向循环仍运转
                .thenWaitUntil(() -> {
                    AnimationBlockEntity be = animationBlockEntity(helper, pos);
                    String state = be.machine().activeStates().get("base");
                    if (!"idle".equals(state)) {
                        helper.fail("expected idle after active completes, got '" + state + "'");
                    }
                })
                // 拆除：BE 被移除
                .thenExecute(() -> helper.setBlock(pos, Blocks.AIR))
                .thenWaitUntil(() -> {
                    if (helper.getLevel().getBlockEntity(pos) != null) {
                        helper.fail("block entity not removed after block destroyed at " + pos);
                    }
                })
                .thenSucceed();
    }

    /**
     * The three programmatic ids (stateMachineId / modelLoc / modelName) survive an NBT round-trip via the
     * static {@link AnimationBlockEntity#writePersistedIds} / {@link AnimationBlockEntity.PersistedIds#read}
     * pair — the contract that lets a chunk-reloaded AnimationBlockEntity rebuild its machine lazily. Runtime
     * machine state is intentionally NOT persisted (rebuilt from the definition id), so only the id triad is
     * asserted. Null ids are omitted (not written as empty strings) and read back as null.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 40)
    public static void persistedIdsSurviveNbtRoundTrip(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        Id machineId = FsmTestRegistration.MACHINE_ID;
        ResourceLocation modelLoc = ResourceLocation.parse("kasuga_lib:models/fsm/test_cube.obj");
        AnimationBlockEntity.writePersistedIds(tag, machineId, modelLoc, "cube");

        AnimationBlockEntity.PersistedIds ids = AnimationBlockEntity.PersistedIds.read(tag);
        if (!machineId.equals(ids.stateMachineId())) {
            helper.fail("stateMachineId not round-tripped: expected " + machineId + ", got " + ids.stateMachineId());
            return;
        }
        if (!modelLoc.equals(ids.modelLoc())) {
            helper.fail("modelLoc not round-tripped: expected " + modelLoc + ", got " + ids.modelLoc());
            return;
        }
        if (!"cube".equals(ids.modelName())) {
            helper.fail("modelName not round-tripped: expected cube, got " + ids.modelName());
            return;
        }

        // nulls are omitted (not written as empty strings) — read-back yields null
        CompoundTag nullTag = new CompoundTag();
        AnimationBlockEntity.writePersistedIds(nullTag, null, null, null);
        AnimationBlockEntity.PersistedIds empty = AnimationBlockEntity.PersistedIds.read(nullTag);
        if (empty.stateMachineId() != null || empty.modelLoc() != null || empty.modelName() != null) {
            helper.fail("null ids should stay null after round-trip, got " + empty);
            return;
        }
        helper.succeed();
    }

    private static AnimationBlockEntity animationBlockEntity(GameTestHelper helper, BlockPos pos) {
        // GameTestHelper.getBlockEntity translates the relative position into absolute world coords
        BlockEntity be = helper.getBlockEntity(pos);
        if (!(be instanceof AnimationBlockEntity animationBe)) {
            helper.fail("block entity at " + pos + " is " + (be == null ? "null" : be.getClass().getName())
                    + ", expected AnimationBlockEntity");
        }
        return (AnimationBlockEntity) be;
    }
}
