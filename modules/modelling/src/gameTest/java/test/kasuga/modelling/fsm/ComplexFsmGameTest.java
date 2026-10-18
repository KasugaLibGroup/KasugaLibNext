package test.kasuga.modelling.fsm;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BlendMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Complex multi-layer FSM game tests: locomotion (idle/walk/run/jump) + combat (windup/hit/recover with
 * lockLayer + hit-count action). Exercises the full feature set: var-driven conditions, triggers,
 * whenComplete chains, on_enter/on_exit actions, lockLayer cross-layer interaction, two independent layer
 * state graphs.
 */
@GameTestHolder
public final class ComplexFsmGameTest {

    private ComplexFsmGameTest() {}

    private static Block complexBlock(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("kasuga_lib:fsm_test_complex_block"));
        if (block == Blocks.AIR) {
            helper.fail("kasuga_lib:fsm_test_complex_block not registered");
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

    private static String loco(StateMachine<?> m) { return m.activeStates().get("locomotion"); }
    private static String combat(StateMachine<?> m) { return m.activeStates().get("combat"); }

    /**
     * Full locomotion cycle: idle → walk (speed=3) → run (speed=8) → walk (speed=3) → idle (speed=0),
     * verifying each transition fires through the BE ticker. Combat layer stays "none" throughout.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 300)
    public static void locomotionCycleThroughWalkRunAndBack(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (machine(helper, pos) == null) {
                        helper.fail("not built");
                    }
                })
                // idle → walk
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 3f))
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk, got " + loco(machine(helper, pos)));
                    }
                })
                // walk → run
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 8f))
                .thenWaitUntil(() -> {
                    if (!"run".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected run, got " + loco(machine(helper, pos)));
                    }
                })
                // run → walk (drop speed)
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 3f))
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk after slow, got " + loco(machine(helper, pos)));
                    }
                })
                // walk → idle (stop)
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 0f))
                .thenWaitUntil(() -> {
                    if (!"idle".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected idle after stop, got " + loco(machine(helper, pos)));
                    }
                })
                // combat untouched throughout
                .thenExecute(() -> {
                    if (!"none".equals(combat(machine(helper, pos)))) {
                        helper.fail("combat should be 'none' throughout, got " + combat(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }

    /**
     * Jump from idle: trigger jump → air(5t) → land(3t) → idle. Tests trigger-driven transitions + whenComplete
     * auto-advance chain on the locomotion layer.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 300)
    public static void jumpChainAirLandIdle(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                .thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.JUMP))
                .thenWaitUntil(() -> {
                    if (!"air".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected air after jump, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"land".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected land after air, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"idle".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected idle after land, got " + loco(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }

    /**
     * Full attack chain: trigger attack → windup(3t, locks locomotion) → hit(2t, HIT_COUNT++) →
     * recover(5t, unlocks on exit) → none. Verifies:
     * <ul>
     *   <li>The combat layer's trigger → whenComplete → whenComplete → whenComplete chain.</li>
     *   <li>HIT_COUNT increments exactly once per attack.</li>
     *   <li>COMBAT_LOCK is set during windup/hit/recover and cleared on recover exit.</li>
     *   <li>locomotion is locked during the entire combat window (can't walk even with speed>0).</li>
     * </ul>
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 400)
    public static void attackChainLocksLocomotionAndCountsHits(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                // set speed>0 but don't attack yet — locomotion should walk
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 3f))
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk before attack, got " + loco(machine(helper, pos)));
                    }
                })
                // trigger attack → combat enters windup, locks locomotion
                .thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.ATTACK_TRIGGER))
                .thenWaitUntil(() -> {
                    var m = machine(helper, pos);
                    if (!"windup".equals(combat(m))) {
                        helper.fail("expected windup, got " + combat(m));
                    }
                })
                // locomotion is now locked — even though speed=3, it can't leave walk
                .thenExecute(() -> {
                    var m = machine(helper, pos);
                    if (!m.isLayerLocked("locomotion")) {
                        helper.fail("locomotion should be locked during combat");
                    }
                })
                // wait for hit state (windup 3t + whenComplete)
                .thenWaitUntil(() -> {
                    if (!"hit".equals(combat(machine(helper, pos)))) {
                        helper.fail("expected hit after windup, got " + combat(machine(helper, pos)));
                    }
                })
                // HIT_COUNT should be 1 (record_hit action on hit.on_enter)
                .thenExecute(() -> {
                    var m = machine(helper, pos);
                    if (m.vars().get(FsmTestRegistration.HIT_COUNT) != 1) {
                        helper.fail("expected HIT_COUNT=1, got " + m.vars().get(FsmTestRegistration.HIT_COUNT));
                    }
                })
                // wait for recover → none (recover 5t + whenComplete)
                .thenWaitUntil(() -> {
                    if (!"none".equals(combat(machine(helper, pos)))) {
                        helper.fail("expected none after recover, got " + combat(machine(helper, pos)));
                    }
                })
                // combat finished: lock released, COMBAT_LOCK cleared
                .thenExecute(() -> {
                    var m = machine(helper, pos);
                    if (m.isLayerLocked("locomotion")) {
                        helper.fail("locomotion should be unlocked after combat");
                    }
                    if (Boolean.TRUE.equals(m.vars().get(FsmTestRegistration.COMBAT_LOCK))) {
                        helper.fail("COMBAT_LOCK should be cleared after combat");
                    }
                })
                // locomotion can now respond to speed changes again
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 8f))
                .thenWaitUntil(() -> {
                    if (!"run".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected run after unlock+speed=8, got " + loco(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }

    /**
     * Two independent layers: locomotion cycles walk↔idle while combat fires a full attack chain —
     * the two layers operate on separate state graphs without interfering.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 400)
    public static void twoLayersIndependentLocomotionAndCombat(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                // start walking + attack at the same time
                .thenExecute(() -> {
                    var m = machine(helper, pos);
                    m.mutableVars().set(FsmTestRegistration.SPEED, 3f);
                    m.trigger(FsmTestRegistration.ATTACK_TRIGGER);
                })
                // locomotion goes to walk (locked soon by combat, but the transition to walk fires first tick)
                .thenWaitUntil(() -> {
                    var m = machine(helper, pos);
                    String l = loco(m);
                    String c = combat(m);
                    // locomotion should have left idle; combat should be in the attack chain
                    if ("idle".equals(l)) {
                        helper.fail("locomotion shouldn't be idle after speed=3, got " + l);
                    }
                    if ("none".equals(c)) {
                        helper.fail("combat shouldn't be none after attack trigger, got " + c);
                    }
                })
                // wait for combat to finish
                .thenWaitUntil(() -> {
                    if (!"none".equals(combat(machine(helper, pos)))) {
                        helper.fail("expected combat none after chain, got " + combat(machine(helper, pos)));
                    }
                })
                // after combat unlock, locomotion is still in walk (speed=3)
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk after combat ends (speed=3), got " + loco(machine(helper, pos)));
                    }
                })
                // stop → idle
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 0f))
                .thenWaitUntil(() -> {
                    if (!"idle".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected idle after stop, got " + loco(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }

    /**
     * Three back-to-back attack chains: each trigger runs the full combat cycle (windup→hit→recover→none), and
     * the {@code record_hit} on_enter action increments HIT_COUNT once per chain. Asserts HIT_COUNT == 3 after
     * the third chain — covers ephemeral-trigger re-fire across cycles + action side-effect accumulation (a
     * single attack only proves the +1 path; this proves it composes).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 600)
    public static void multipleAttacksAccumulateHitCount(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        GameTestSequence seq = helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); });
        for (int i = 0; i < 3; i++) {
            int attackNumber = i + 1;
            // Wait for windup (chain started) THEN none (chain finished) per iteration. Waiting only for "none"
            // would pass instantly on the first iteration — combat's initial state IS "none" — and the trigger
            // would be swept before the chain ever ran (HIT_COUNT would stay 0).
            seq = seq.thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.ATTACK_TRIGGER))
                    .thenWaitUntil(() -> {
                        String c = combat(machine(helper, pos));
                        if (!"windup".equals(c)) {
                            helper.fail("attack #" + attackNumber + ": expected windup after trigger, got " + c);
                        }
                    })
                    .thenWaitUntil(() -> {
                        String c = combat(machine(helper, pos));
                        if (!"none".equals(c)) {
                            helper.fail("attack #" + attackNumber
                                    + ": expected combat back to none after chain, got " + c);
                        }
                    });
        }
        seq.thenExecute(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    int hits = m.vars().get(FsmTestRegistration.HIT_COUNT);
                    if (hits != 3) {
                        helper.fail("expected HIT_COUNT=3 after 3 attacks, got " + hits);
                    }
                })
                .thenSucceed();
    }

    /**
     * The direct {@code run_to_idle} edge (when {@code is_standing} fires after running): from run, dropping
     * speed to 0 returns to idle <em>without</em> passing through walk. Distinct from
     * {@link #locomotionCycleThroughWalkRunAndBack}, which hops run→walk→idle. Run is reached via walk first
     * (there is no idle→run edge, and idle→walk needs {@code is_walking} = speed ≤ 5, so a jump straight to
     * speed=8 from idle would stall); then speed=0 exercises run_to_idle directly — proven by construction,
     * since {@code run_to_walk} cannot fire at speed=0 ({@code is_walking} false), so reaching idle from run
     * requires the run_to_idle transition.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void runToIdleDirectSkipsWalk(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                // accelerate through walk (idle→run is not a direct edge)
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 3f))
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk at speed=3, got " + loco(machine(helper, pos)));
                    }
                })
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 8f))
                .thenWaitUntil(() -> {
                    if (!"run".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected run at speed=8, got " + loco(machine(helper, pos)));
                    }
                })
                // stop from run: run_to_idle fires directly (run_to_walk can't at speed=0)
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 0f))
                .thenWaitUntil(() -> {
                    if (!"idle".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected idle after stop from run (direct run_to_idle), got "
                                + loco(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }

    /**
     * Jump from walk: walk→air (jump trigger) → land (when_complete) → idle (when_complete), then because speed
     * is still 3 the idle_to_walk condition re-fires → walk resumes. Covers the walk_to_air trigger edge and
     * proves locomotion recovers its condition-driven state after a jump interrupt (distinct from jumping from
     * idle in {@link #jumpChainAirLandIdle}).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void jumpFromWalkResumesWalkAfterLanding(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 3f))
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk at speed=3, got " + loco(machine(helper, pos)));
                    }
                })
                .thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.JUMP))
                .thenWaitUntil(() -> {
                    if (!"air".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected air after jump from walk, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"land".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected land after air, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"idle".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected idle after land, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"walk".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected walk to resume after landing (speed still 3), got "
                                + loco(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }

    /**
     * While the combat layer holds the locomotion lock (set in windup.on_enter, cleared in recover.on_exit), a
     * jump trigger raised against locomotion must NOT fire — the locked layer skips transition evaluation
     * entirely and the ephemeral jump var is swept at tick end. Sampled every tick during the combat window:
     * locomotion never enters "air". The jump is raised only after windup is observed (so the lock is already
     * in effect — avoids the 1-tick race where locomotion ticks before combat's on_enter runs).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 500)
    public static void combatLockBlocksJumpTrigger(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        String[] sawAir = {null};
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                // trigger an attack — combat enters windup and start_combat_lock freezes locomotion next tick
                .thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.ATTACK_TRIGGER))
                .thenWaitUntil(() -> {
                    if (!"windup".equals(combat(machine(helper, pos)))) {
                        helper.fail("expected windup to engage the locomotion lock, got "
                                + combat(machine(helper, pos)));
                    }
                })
                // NOW the lock is in effect — raise jump. The locked locomotion layer must swallow it.
                .thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.JUMP))
                // poll the whole combat window: record if locomotion ever reaches "air", wait for combat to end
                .thenWaitUntil(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    String l = loco(m);
                    String c = combat(m);
                    if ("air".equals(l)) {
                        sawAir[0] = "air";
                    }
                    if (!"none".equals(c)) {
                        helper.fail("waiting for combat chain to finish (locomotion=" + l + ", combat=" + c + ")");
                    }
                    if (sawAir[0] != null) {
                        helper.fail("locomotion entered air despite the combat lock — jump was not blocked");
                    }
                })
                .thenExecute(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    if (sawAir[0] != null) {
                        helper.fail("locomotion entered air during the combat window despite the lock");
                    }
                    if (!"idle".equals(loco(m))) {
                        helper.fail("locomotion should still be idle after combat (never moved), got " + loco(m));
                    }
                })
                .thenSucceed();
    }

    /**
     * Structural reads on a freshly built complex machine: layer blend modes/weights (locomotion=BASE,
     * combat=OVERRIDE, both weight 1.0), no lock engaged, and the initial active states are idle/none. Pure
     * read-surface coverage for {@code layerMode}/{@code layerWeight}/{@code isLayerLocked}.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void layerModesAndWeightsReadable(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                .thenExecute(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    if (m.layerMode("locomotion") != BlendMode.BASE) {
                        helper.fail("locomotion mode should be BASE, got " + m.layerMode("locomotion"));
                    }
                    if (m.layerMode("combat") != BlendMode.OVERRIDE) {
                        helper.fail("combat mode should be OVERRIDE, got " + m.layerMode("combat"));
                    }
                    if (m.layerWeight("locomotion") != 1f) {
                        helper.fail("locomotion weight should be 1.0, got " + m.layerWeight("locomotion"));
                    }
                    if (m.layerWeight("combat") != 1f) {
                        helper.fail("combat weight should be 1.0, got " + m.layerWeight("combat"));
                    }
                    if (m.isLayerLocked("locomotion") || m.isLayerLocked("combat")) {
                        helper.fail("no layer should be locked on a freshly built machine");
                    }
                    if (!"idle".equals(loco(m)) || !"none".equals(combat(m))) {
                        helper.fail("initial states should be idle/none, got " + m.activeStates());
                    }
                })
                .thenSucceed();
    }

    /**
     * Imperative {@code goTo} switch (scripting/JSON-friendly, instant): force locomotion to "air" out of the
     * normal idle state, then let the air→land→idle when_complete chain run naturally — proving the imperative
     * switch takes effect on the next tick and the machine continues normally afterward. "air" is chosen because
     * it has a multi-tick duration window (so the post-goTo state is reliably observable, unlike walk/run which
     * a same-tick condition would immediately pull back to idle at speed=0).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void imperativeGoToAirThenChainsBack(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, complexBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> { if (machine(helper, pos) == null) helper.fail("not built"); })
                .thenExecute(() -> machine(helper, pos).goTo("locomotion", "air"))
                .thenWaitUntil(() -> {
                    if (!"air".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected air after goTo, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"land".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected land after air duration, got " + loco(machine(helper, pos)));
                    }
                })
                .thenWaitUntil(() -> {
                    if (!"idle".equals(loco(machine(helper, pos)))) {
                        helper.fail("expected idle after land, got " + loco(machine(helper, pos)));
                    }
                })
                .thenSucceed();
    }
}
