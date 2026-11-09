package test.kasuga.modelling;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.function.Supplier;

@Context
public class ModellingContentTest {


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

    public static BlockReg<FanBlock> EXAMPLE_FAN_BLOCK;
    public static BlockEntityReg<FanBlockEntity> EXAMPLE_FAN_BE;

    @PostConstruct
    public void init() {
        // 在方法内构造，规避静态字段初始化器的前向/自引用限制；
        // 注册本身由 REGISTRY 在 KasugaLibStartupEvent 时统一派发。
        // The data-driven sibling (kasuga_lib:example_fan_data_driven) is registered by
        // FanDataDrivenFactories from the content file — same classes, params-selected machine/model.
        EXAMPLE_FAN_BE = FanProxyDefinitions.registerWhenReady(BlockEntityReg.of(
                "example_fan",
                (pos, state) -> new FanBlockEntity(EXAMPLE_FAN_BE.getEntry(), pos, state)
        ).validBlocks(Collections.singletonList((Supplier<Block>) () -> EXAMPLE_FAN_BLOCK.getEntry()))
                .setParent(ModellingTestApplication.registry));

        EXAMPLE_FAN_BLOCK = BlockReg.of(
                "example_fan",
                props -> new FanBlock(props, () -> EXAMPLE_FAN_BE.getEntry())
        ).withDefaultBlockItem("example_fan")
                .setParent(ModellingTestApplication.registry);

        // v2.0 FSM registrations (code-constructed; no data/<modid>/kasugalib JSON).
        FsmRegistries.GLOBAL.vars().register(FanFixtures.CYCLE);
        FsmRegistries.GLOBAL.vars().register(FanVarProvider.CURRENT_SPEED);
        FsmRegistries.GLOBAL.vars().register(FanVarProvider.ANGLE);
        FsmRegistries.GLOBAL.definitions().register(FanFixtures.MACHINE_ID, fanMachineDefinition());
        FsmRegistries.GLOBAL.clips().register(
                FanFixtures.CLIP_ID,
                ClipSampler.INSTANCE,
                FanAnimationClipFactory.fanClip());
    }

    /** Parse {@link #FAN_MACHINE_JSON} through the codec (must round-trip; throws on any decode error). */
    static StateMachineDefinition fanMachineDefinition() {
        JsonElement element = JsonParser.parseString(FAN_MACHINE_JSON);
        return StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }
}
