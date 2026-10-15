package test.kasuga.modelling.fsm;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.mc.multiplexer.McContext;
import lib.kasuga.rendering.models.mc.multiplexer.McVariant;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Multiplexer;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.MuxState;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.SelectorPredicates;
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
 * 服务端 game test：覆盖强类型 {@link StateVar} 状态层。两套互补：
 * <ul>
 *   <li>factory-direct：{@link DefinitionStateMachineFactory} 建机 + 手动 tick，隔离 FSM 逻辑；</li>
 *   <li>block-bound：放 {@code kasuga_lib:fsm_test_typed_block}（BE 绑定同一机器定义），由 BE ticker
 *       驱动，覆盖 data-driven → {@link AnimationBlockEntity} 惰性建机 → 服务端 tick 的完整绑定链路。</li>
 * </ul>
 * 覆盖项：{@code state_vars} 解析与默认值、类型安全 get/set/remove、由读取变量的条件守卫的过渡、
 * ephemeral 触发器变量同 tick 触发过渡并在 tick 末清除。
 */
@GameTestHolder
public final class FsmTypedStateGameTest {

    private static final ResourceLocation TYPED_BLOCK_ID = ResourceLocation.parse("kasuga_lib:fsm_test_typed_block");

    private FsmTypedStateGameTest() {
    }

    //region helpers

    private static StateMachine<Object> buildTypedMachine() {
        StateMachineDefinition definition = FsmRegistries.GLOBAL.definitions().get(FsmTestRegistration.TYPED_MACHINE_ID);
        if (definition == null) {
            throw new IllegalStateException("typed definition " + FsmTestRegistration.TYPED_MACHINE_ID + " not registered");
        }
        return new DefinitionStateMachineFactory<Object>(FsmRegistries.GLOBAL.functions()).build(new Object(), definition, null);
    }

    private static Block typedBlock(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(TYPED_BLOCK_ID);
        if (block == Blocks.AIR) {
            helper.fail("kasuga_lib:fsm_test_typed_block not registered — FsmTestRegistration missing");
        }
        return block;
    }

    private static AnimationBlockEntity blockEntity(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getBlockEntity(pos);
        if (!(be instanceof AnimationBlockEntity)) {
            helper.fail("block entity at " + pos + " is " + (be == null ? "null" : be.getClass().getName())
                    + ", expected AnimationBlockEntity");
        }
        return (AnimationBlockEntity) be;
    }

    private static StateMachine<?> machine(GameTestHelper helper, BlockPos pos) {
        StateMachine<?> m = blockEntity(helper, pos).machine();
        if (m == null) {
            helper.fail("machine not built at " + pos);
        }
        return m;
    }

    //endregion

    //region factory-direct (manual tick) — isolates the FSM logic from the BE

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void stateVarsResolveAndAreTypedReadWrite(GameTestHelper helper) {
        StateMachine<Object> m = buildTypedMachine();

        // defaults read through with the declared type
        if (m.vars().get(FsmTestRegistration.SPEED) != 0f) {
            helper.fail("speed default should be 0f, got " + m.vars().get(FsmTestRegistration.SPEED));
            return;
        }
        if (Boolean.TRUE.equals(m.vars().get(FsmTestRegistration.ARMED))) {
            helper.fail("armed default should be false");
            return;
        }
        if (m.vars().has(FsmTestRegistration.SPEED)) {
            helper.fail("speed should not be 'has' before set");
            return;
        }
        m.mutableVars().set(FsmTestRegistration.SPEED, 1.25f);
        if (m.vars().get(FsmTestRegistration.SPEED) != 1.25f) {
            helper.fail("speed should read back 1.25f, got " + m.vars().get(FsmTestRegistration.SPEED));
            return;
        }
        if (!m.vars().has(FsmTestRegistration.SPEED)) {
            helper.fail("speed should be 'has' after set");
            return;
        }
        m.mutableVars().remove(FsmTestRegistration.SPEED);
        if (m.vars().get(FsmTestRegistration.SPEED) != 0f) {
            helper.fail("speed should fall back to default after remove");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void varDrivenConditionFiresTransition(GameTestHelper helper) {
        StateMachine<Object> m = buildTypedMachine();
        m.mutableVars().set(FsmTestRegistration.SPEED, 1f); // is_moving -> true
        helper.startSequence()
                .thenWaitUntil(() -> {
                    m.tick();
                    if (!"moving".equals(m.activeStates().get("base"))) {
                        helper.fail("expected moving, got " + m.activeStates());
                    }
                })
                .thenWaitUntil(() -> {
                    m.tick();
                    if (!"idle".equals(m.activeStates().get("base"))) {
                        helper.fail("expected idle after moving duration, got " + m.activeStates());
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void ephemeralTriggerFiresAndClears(GameTestHelper helper) {
        StateMachine<Object> m = buildTypedMachine();
        helper.startSequence()
                .thenExecute(() -> m.trigger(FsmTestRegistration.ATTACK))
                .thenWaitUntil(() -> {
                    m.tick();
                    if (!"strike".equals(m.activeStates().get("base"))) {
                        helper.fail("expected strike after trigger, got " + m.activeStates());
                    }
                })
                .thenWaitUntil(() -> {
                    m.tick();
                    if (!"idle".equals(m.activeStates().get("base"))) {
                        helper.fail("expected idle after strike duration, got " + m.activeStates());
                    }
                })
                .thenExecute(() -> {
                    if (m.vars().has(FsmTestRegistration.ATTACK)) {
                        helper.fail("ephemeral ATTACK trigger should be cleared after the tick");
                    }
                })
                .thenSucceed();
    }

    //endregion

    //region block-bound — driven by the real AnimationBlockEntity server ticker

    /** Placing the typed block lazily builds a machine whose declared state_vars resolve with defaults. */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void blockBoundBuildsAndTypedReadWrite(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, typedBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (blockEntity(helper, pos).machine() == null) {
                        helper.fail("machine not built at " + pos);
                    }
                })
                .thenExecute(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    // defaults read through
                    if (m.vars().get(FsmTestRegistration.SPEED) != 0f) {
                        helper.fail("speed default should be 0f, got " + m.vars().get(FsmTestRegistration.SPEED));
                        return;
                    }
                    if (Boolean.TRUE.equals(m.vars().get(FsmTestRegistration.ARMED))) {
                        helper.fail("armed default should be false");
                        return;
                    }
                    // typed set/get + has, then remove returns to default
                    m.mutableVars().set(FsmTestRegistration.SPEED, 2.5f);
                    if (m.vars().get(FsmTestRegistration.SPEED) != 2.5f) {
                        helper.fail("speed should read 2.5f, got " + m.vars().get(FsmTestRegistration.SPEED));
                        return;
                    }
                    if (!m.vars().has(FsmTestRegistration.SPEED)) {
                        helper.fail("speed should be 'has' after set");
                        return;
                    }
                    m.mutableVars().remove(FsmTestRegistration.SPEED);
                    if (m.vars().get(FsmTestRegistration.SPEED) != 0f) {
                        helper.fail("speed should fall back to default after remove");
                    }
                })
                .thenSucceed();
    }

    /** A var-driven condition transition fires under the BE ticker: set speed → idle→moving→idle. */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 300)
    public static void blockBoundVarDrivenTransition(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, typedBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (machine(helper, pos) == null) {
                        helper.fail("machine not built at " + pos);
                    }
                })
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 1f))
                .thenWaitUntil(() -> {
                    String state = machine(helper, pos).activeStates().get("base");
                    if (!"moving".equals(state)) {
                        helper.fail("expected moving after speed set, got " + state);
                    }
                })
                // drop speed so the machine settles back to idle (instead of cycling forever)
                .thenExecute(() -> machine(helper, pos).mutableVars().remove(FsmTestRegistration.SPEED))
                .thenWaitUntil(() -> {
                    String state = machine(helper, pos).activeStates().get("base");
                    if (!"idle".equals(state)) {
                        helper.fail("expected idle after moving duration, got " + state);
                    }
                })
                .thenSucceed();
    }

    /** An ephemeral trigger fires a transition under the BE ticker and is cleared at the tick end. */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 300)
    public static void blockBoundEphemeralTriggerTransition(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, typedBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (machine(helper, pos) == null) {
                        helper.fail("machine not built at " + pos);
                    }
                })
                .thenExecute(() -> machine(helper, pos).trigger(FsmTestRegistration.ATTACK))
                .thenWaitUntil(() -> {
                    String state = machine(helper, pos).activeStates().get("base");
                    if (!"strike".equals(state)) {
                        helper.fail("expected strike after trigger, got " + state);
                    }
                })
                .thenWaitUntil(() -> {
                    String state = machine(helper, pos).activeStates().get("base");
                    if (!"idle".equals(state)) {
                        helper.fail("expected idle after strike duration, got " + state);
                    }
                })
                .thenExecute(() -> {
                    if (machine(helper, pos).vars().has(FsmTestRegistration.ATTACK)) {
                        helper.fail("ephemeral ATTACK trigger should be cleared after the tick");
                    }
                })
                .thenSucceed();
    }

    //endregion

    //region full-chain integration: data-driven + condition + action + var through the BE ticker

    /**
     * The FULL data-driven chain through a real block entity: JSON definition (with inline state_vars +
     * on_enter action reference) → BE builds machine from the registered definition → BE ticker drives it →
     * condition (is_moving, reads the SPEED var) evaluates → idle→moving transition fires → on_enter action
     * (record_active) writes ACTION_RAN → the var is readable from outside. This ties data-driven, vars,
     * conditions, actions, and BE binding into one end-to-end game test.
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 200)
    public static void blockBoundConditionActionVarFullChain(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, typedBlock(helper));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (machine(helper, pos) == null) {
                        helper.fail("machine not built at " + pos);
                    }
                })
                // set SPEED → the is_moving condition will evaluate true on the next tick
                .thenExecute(() -> machine(helper, pos).mutableVars().set(FsmTestRegistration.SPEED, 1f))
                .thenWaitUntil(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    if (!"moving".equals(m.activeStates().get("base"))) {
                        helper.fail("expected moving after speed set, got " + m.activeStates());
                    }
                })
                // the on_enter action (kasuga_lib:fsm_test/record_active) should have written ACTION_RAN=true
                .thenExecute(() -> {
                    StateMachine<?> m = machine(helper, pos);
                    if (!Boolean.TRUE.equals(m.vars().get(FsmTestRegistration.ACTION_RAN))) {
                        helper.fail("on_enter action didn't write ACTION_RAN — full chain broken");
                    }
                })
                .thenSucceed();
    }

    //endregion

    //region multiplexer

    /**
     * Verifies the multiplexer (stateless variant selector) works in a real server context: defines off/on
     * variants, advances with an unpowered context (→ off) then a powered one (→ on), asserting the selected
     * variant each time. This is the non-FSM binding — it composes with the FSM (host picks a variant, then
     * points the FSM's PoseSink at it).
     */
    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void multiplexerSelectsVariantInServerContext(GameTestHelper helper) {
        Blackboard.Key<Boolean> flag = Blackboard.Key.of("powered");
        Multiplexer<McContext, McVariant> mux = Multiplexer.define(McVariant::new, m -> {
            McVariant off = m.variant("off", v -> v.model(ResourceLocation.fromNamespaceAndPath("kasuga_lib", "off")));
            McVariant on = m.variant("on", v -> v.model(ResourceLocation.fromNamespaceAndPath("kasuga_lib", "on")));
            m.transition(off, on, t -> t.when(SelectorPredicates.dataFlag(flag)));
            m.transition(on, off, t -> t.when(in -> !Boolean.TRUE.equals(in.data().get(flag))));
            m.initial(off);
        });

        MuxState<McVariant> state = mux.newState();
        McContext unpowered = new McContext(
                java.util.Map.of(), java.util.List.of(), 0, 0L, java.util.Set.of());
        mux.advance(state, unpowered, 0f);
        if (mux.variant("off") != state.current()) {
            helper.fail("expected off initially");
            return;
        }

        McContext powered = new McContext(
                java.util.Map.of(), java.util.List.of(), 15, 0L, java.util.Set.of());
        powered.data().put(flag, true);
        mux.advance(state, powered, 0f);
        if (mux.variant("on") != state.current()) {
            helper.fail("expected on after flag set");
            return;
        }

        powered.data().put(flag, false);
        mux.advance(state, powered, 0f);
        if (mux.variant("off") != state.current()) {
            helper.fail("expected off after flag cleared");
            return;
        }
        helper.succeed();
    }

    //endregion
}
