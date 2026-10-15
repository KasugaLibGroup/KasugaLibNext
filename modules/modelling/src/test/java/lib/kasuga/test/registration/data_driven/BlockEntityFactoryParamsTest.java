package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmBlockEntityFactories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2.5: {@code fsm_be} / {@code fsm_block} 内置工厂（由 modelling 的
 * {@code FsmBlockEntityFactories} 注册）。验证工厂从 params 读 {@code state_machine} /
 * {@code model} / {@code model_name}，缺失 {@code state_machine} 时 warn 但不崩。
 *
 * <p>注：BE 类型实例的创建需要注册期（{@code getEntry()}），纯 JVM 单测不触发；机器 id 捕获的
 * 端到端验证由 contentTesting 冒烟验收（放块 → 「machine built」日志）覆盖。
 *
 * <p>This lives in modelling's own test source set because it asserts modelling's built-in
 * {@code fsm_block}/{@code fsm_be} factories, which data-driven no longer depends on.
 */
class BlockEntityFactoryParamsTest {

    @BeforeAll
    static void loadFactories() {
        // Register the FSM built-ins directly (idempotent). DataDrivenTestFactories' static registration
        // block only fires when Micronaut instantiates that @Context bean (FML runtime); in a pure-JVM gate
        // that never happens, so register here.
        FsmBlockEntityFactories.registerBuiltin();
        assertNotNull(FactoryRegistry.getBlockEntityFactory("fsm_be"), "fsm_be factory must be registered");
        assertNotNull(FactoryRegistry.get("fsm_block"), "fsm_block factory must be registered");
    }

    @Test
    void fsmBeWithFullParamsCreatesReg() {
        JsonObject params = new JsonObject();
        params.addProperty("state_machine", "kasuga_lib:fsm_test_panel");
        params.addProperty("model", "kasuga_lib_test:models/fsm/test_cube.obj");
        params.addProperty("model_name", "cube");

        Reg<?, ?> reg = create("test:fsm_be_full", params);

        assertTrue(reg instanceof BlockEntityReg, "fsm_be factory must produce a BlockEntityReg");
    }

    @Test
    void fsmBeMissingStateMachineWarnsButDoesNotCrash() {
        JsonObject params = new JsonObject();
        params.addProperty("model", "kasuga_lib_test:models/fsm/test_cube.obj");

        assertNotNull(create("test:fsm_be_no_machine", params), "missing state_machine must not break creation");
    }

    @Test
    void fsmBeNullParamsDoesNotCrash() {
        assertNotNull(create("test:fsm_be_null_params", null));
    }

    @Test
    void fsmBeNonStringStateMachineIsIgnored() {
        JsonObject params = new JsonObject();
        params.add("state_machine", new JsonObject()); // 非字符串：readResourceLocation 判空忽略

        assertNotNull(create("test:fsm_be_non_string", params));
    }

    @Test
    void fsmBlockCreatesReg() {
        Reg<?, ?> reg = FactoryRegistry.get("fsm_block").create("test:fsm_block_unit", null);
        assertNotNull(reg, "fsm_block factory must produce a block reg");
    }

    private static Reg<?, ?> create(String id, JsonObject params) {
        return FactoryRegistry.getBlockEntityFactory("fsm_be").create(id, () -> new net.minecraft.world.level.block.Block[0], params);
    }
}
