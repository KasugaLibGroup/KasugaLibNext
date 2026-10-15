package test.kasuga.modelling.fsm;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * In-game data-driven pipeline test: the production {@code StateMachineDefinitionLoader} reads
 * {@code data/kasuga_lib/state_machines/gametest_loader.json} (a gameTest resource packed into the kasuga_lib
 * data pack) during the real dedicated-server bootstrap and registers it into {@link FsmRegistries#GLOBAL};
 * a block bound to that id ({@code kasuga_lib:fsm_test_loader_block}) then lazily builds + ticks a machine from
 * the LOADER-loaded definition.
 *
 * <p>This proves the full resource-pack → loader → registry → block → BE → ticker chain in the actual server
 * runtime — distinct from {@link FsmTestRegistration}'s inline-registered fixtures (which bypass the loader)
 * and from the loader unit test (which uses a stub {@code ResourceManager}).
 */
@GameTestHolder
public final class FsmDataDrivenGameTest {

    private static final ResourceLocation LOADER_BLOCK_ID =
            ResourceLocation.parse("kasuga_lib:fsm_test_loader_block");

    private FsmDataDrivenGameTest() {
    }

    private static Block loaderBlock(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(LOADER_BLOCK_ID);
        if (block == Blocks.AIR) {
            helper.fail(LOADER_BLOCK_ID + " not registered");
        }
        return block;
    }

    private static StateMachine<?> machine(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getBlockEntity(pos);
        if (!(be instanceof AnimationBlockEntity)) {
            helper.fail("expected AnimationBlockEntity at " + pos + ", got "
                    + (be == null ? "null" : be.getClass().getName()));
        }
        StateMachine<?> m = ((AnimationBlockEntity) be).machine();
        if (m == null) {
            helper.fail("machine not built at " + pos);
        }
        return m;
    }

    /**
     * The loader ran on the real server bootstrap and registered {@code kasuga_lib:gametest_loader} from the
     * gameTest resource pack (RESOURCE source — not an inline SCRIPT registration).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void loaderPickedUpGameTestResourceDefinition(GameTestHelper helper) {
        StateMachineDefinition def = FsmRegistries.GLOBAL.definitions().get(FsmTestRegistration.LOADER_MACHINE_ID);
        if (def == null) {
            helper.fail("loader did not register " + FsmTestRegistration.LOADER_MACHINE_ID
                    + " — StateMachineDefinitionLoader did not run on the gameTest resource pack");
            return;
        }
        helper.succeed();
    }

    /**
     * A block bound to the loader-loaded definition builds a machine from it and the BE ticker drives the
     * idle(2) → active(2) → idle when_complete cycle. The full data-driven-in-game chain.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void blockBoundToLoaderDefinitionTicks(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, loaderBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                .thenExecute(() -> {
                    if (!"idle".equals(machine(helper, pos).activeStateId("base"))) {
                        helper.fail("expected initial idle, got " + machine(helper, pos).activeStateId("base"));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"active".equals(machine(helper, pos).activeStateId("base"))) {
                        helper.fail("expected active after idle duration, got "
                                + machine(helper, pos).activeStateId("base"));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"idle".equals(machine(helper, pos).activeStateId("base"))) {
                        helper.fail("expected idle after active duration, got "
                                + machine(helper, pos).activeStateId("base"));
                    }
                })
                .thenSucceed();
    }
}
