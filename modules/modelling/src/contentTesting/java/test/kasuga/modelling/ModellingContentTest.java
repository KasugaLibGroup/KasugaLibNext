package test.kasuga.modelling;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import net.minecraft.core.registries.Registries;
import lib.kasuga.rendering.models.mc.content.block.KasugaBlockEntity;
import lib.kasuga.rendering.models.mc.registry.PipelineBindingRegistry;
import lib.kasuga.rendering.models.mc.registry.pipeline_binding.BlockPipelineBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.function.Supplier;

@Context
public class ModellingContentTest {

    
    public static final ResourceLocation TEST_FAN_BE_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_fan_be");

    public static final ResourceLocation TEST_FAN_BE_MODEL =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/be/test_fan_be.geo.json");

    public static final ResourceLocation TEST_FAN_FORMULA_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_fan_formula");

    public static final ResourceLocation TEST_FAN_FORMULA_MODEL =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/be/test_fan_be.bbmodel");

    /**
     * v2.0 fan machine definition — code-constructed JSON parsed through {@link StateMachineDefinition#CODEC}
     * (round-trips exactly, no resource file). The two derived vars ({@code kasuga_lib:fan/current_speed},
     * {@code kasuga_lib:fan/angle}) are declared as <b>references</b> to the registered
     * {@link FanVarProvider} specs so their {@code externalWritable=false} attribute survives resolution and
     * the declared vars ARE the provider's specs (single source of truth); the inline form would build ids
     * prefixed with the machine path, diverging from the provider's ids.
     */
    static final String FAN_MACHINE_JSON = """
            {
              "id": "kasuga_lib:fan_machine",
              "state_vars": [
                { "name": "cycle", "type": "bool", "default": false, "ephemeral": true, "reference": "kasuga_lib:fan/cycle" },
                { "name": "kasuga_lib:fan/current_speed", "reference": "kasuga_lib:fan/current_speed" },
                { "name": "kasuga_lib:fan/angle", "reference": "kasuga_lib:fan/angle" }
              ],
              "layers": [
                {
                  "id": "gear",
                  "mode": "base",
                  "initial_state": "off",
                  "states": [
                    { "id": "off", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } },
                    { "id": "g1", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } },
                    { "id": "g2", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } },
                    { "id": "g3", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } }
                  ],
                  "transitions": [
                    { "id": "off_to_g1", "from": "off", "to": "g1", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
                    { "id": "g1_to_g2", "from": "g1", "to": "g2", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
                    { "id": "g2_to_g3", "from": "g2", "to": "g3", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
                    { "id": "g3_to_off", "from": "g3", "to": "off", "trigger_on": "cycle", "cross_fade_seconds": 0.25 }
                  ]
                }
              ]
            }
            """;

    public static BlockReg<TestFanBeBlock> TEST_FAN_BE_BLOCK;
    public static BlockEntityReg<KasugaBlockEntity> TEST_FAN_BE_BE;

    public static BlockReg<FanBlock> TEST_FAN_FORMULA_BLOCK;
    public static BlockEntityReg<FanBlockEntity> TEST_FAN_FORMULA_BE;

    @PostConstruct
    public void init() {
        // 在方法内构造，规避静态字段初始化器的前向/自引用限制；
        // 注册本身由 REGISTRY 在 KasugaLibStartupEvent 时统一派发。
        TEST_FAN_BE_BE = BlockEntityReg.of(
                "test_fan_be",
                (pos, state) -> new KasugaBlockEntity(TEST_FAN_BE_BE.getEntry(), pos, state)
        ).validBlocks(Collections.singletonList((Supplier<Block>) () -> TEST_FAN_BE_BLOCK.getEntry()))
                .setParent(ModellingTestApplication.registry);

        TEST_FAN_BE_BLOCK = BlockReg.of(
                "test_fan_be",
                props -> new TestFanBeBlock(props, () -> TEST_FAN_BE_BE.getEntry())
        ).withDefaultBlockItem("test_fan_be")
                .setParent(ModellingTestApplication.registry);

        // 绑定是纯数据（RL → features/behavior），无需等方块注册完成。
        // 方块 id 固定为 kasuga_lib:test_fan_be，onLoad 时经 BuiltInRegistries 反查。
        PipelineBindingRegistry.registerBlock(TEST_FAN_BE_ID, BlockPipelineBinding.single(
                TEST_FAN_BE_MODEL,
                null
        ));

        TEST_FAN_FORMULA_BE = BlockEntityReg.of(
                "test_fan_formula",
                (pos, state) -> new FanBlockEntity(TEST_FAN_FORMULA_BE.getEntry(), pos, state)
        ).validBlocks(Collections.singletonList((Supplier<Block>) () -> TEST_FAN_FORMULA_BLOCK.getEntry()))
                .setParent(ModellingTestApplication.registry);

        TEST_FAN_FORMULA_BLOCK = BlockReg.of(
                "test_fan_formula",
                props -> new FanBlock(props, () -> TEST_FAN_FORMULA_BE.getEntry())
        ).withDefaultBlockItem("test_fan_formula")
                .setParent(ModellingTestApplication.registry);

        PipelineBindingRegistry.registerBlock(TEST_FAN_FORMULA_ID, BlockPipelineBinding.single(
                TEST_FAN_FORMULA_MODEL,
                null
        ));

        // v2.0 FSM registrations (code-constructed; no data/<modid>/kasugalib JSON).
        FsmRegistries.GLOBAL.vars().register(FanBlockEntity.CYCLE);
        FsmRegistries.GLOBAL.vars().register(FanVarProvider.CURRENT_SPEED);
        FsmRegistries.GLOBAL.vars().register(FanVarProvider.ANGLE);
        FsmRegistries.GLOBAL.definitions().register(FanBlockEntity.FAN_MACHINE_ID, fanMachineDefinition());
        FsmRegistries.GLOBAL.clips().register(
                Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm"),
                ClipSampler.INSTANCE,
                FanAnimationClipFactory.fanClip());
    }

    /** Parse {@link #FAN_MACHINE_JSON} through the codec (must round-trip; throws on any decode error). */
    static StateMachineDefinition fanMachineDefinition() {
        JsonElement element = JsonParser.parseString(FAN_MACHINE_JSON);
        return StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }
}