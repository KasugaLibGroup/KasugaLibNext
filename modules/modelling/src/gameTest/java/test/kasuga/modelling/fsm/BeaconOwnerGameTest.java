package test.kasuga.modelling.fsm;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
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
 * BE-as-owner demo: a block bound to {@code kasuga_lib:beacon} (registered in {@link FsmTestRegistration})
 * builds a machine whose owner is the {@link AnimationBlockEntity} itself, and whose guards read that BE via
 * {@code ctx.owner()} — specifically {@code level.hasNeighborSignal(be.getBlockPos())}. Powering the block with
 * a redstone source drives dim→lit; removing it drives lit→dim. This is the "FSM reasons about the host BE
 * itself" pattern, distinct from the typed/complex fixtures (which read StateVars) and from scripting (whose
 * owner is a JS object).
 */
@GameTestHolder
public final class BeaconOwnerGameTest {

    private static final ResourceLocation BEACON_BLOCK_ID =
            ResourceLocation.parse("kasuga_lib:fsm_test_beacon_block");

    private BeaconOwnerGameTest() {
    }

    private static Block beaconBlock(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(BEACON_BLOCK_ID);
        if (block == Blocks.AIR) {
            helper.fail(BEACON_BLOCK_ID + " not registered");
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

    private static String state(GameTestHelper helper, BlockPos pos) {
        return machine(helper, pos).activeStateId("main");
    }

    /**
     * Place the beacon (initial dim), power it with an adjacent redstone block (the guard reads the BE via
     * {@code ctx.owner()} → {@code hasNeighborSignal} true → dim→lit), then remove the power (→ lit→dim).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 300)
    public static void beaconReadsRedstonePowerViaBeOwner(GameTestHelper helper) {
        BlockPos beacon = new BlockPos(1, 1, 1);
        BlockPos power = new BlockPos(2, 1, 1);   // adjacent (east) — a neighbor for hasNeighborSignal
        helper.setBlock(beacon, beaconBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, beacon) == null) helper.fail("not built"); })
                .thenExecute(() -> {
                    if (!"dim".equals(state(helper, beacon))) {
                        helper.fail("expected initial dim, got " + state(helper, beacon));
                    }
                })
                // power the BE: an adjacent redstone block → hasNeighborSignal true → beacon_powered guard fires
                .thenExecute(() -> helper.setBlock(power, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> {
                    if (!"lit".equals(state(helper, beacon))) {
                        helper.fail("expected lit after redstone power (guard read BE via ctx.owner()), got "
                                + state(helper, beacon));
                    }
                })
                // remove power → beacon_unpowered guard fires → lit→dim
                .thenExecute(() -> helper.setBlock(power, Blocks.AIR))
                .thenWaitUntil(() -> {
                    if (!"dim".equals(state(helper, beacon))) {
                        helper.fail("expected dim after removing power, got " + state(helper, beacon));
                    }
                })
                .thenSucceed();
    }
}
