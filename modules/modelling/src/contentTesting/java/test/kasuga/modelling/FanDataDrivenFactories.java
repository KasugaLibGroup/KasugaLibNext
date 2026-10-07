package test.kasuga.modelling;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.rendering.models.mc.registry.PipelineBindingRegistry;
import lib.kasuga.rendering.models.mc.registry.pipeline_binding.BlockPipelineBinding;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Factories for the data-driven fan fixture ({@code kasuga_lib:test_fan_formula_data_driven}): the
 * block half ({@code fan_block}) and the block entity half ({@code fan_be}).
 *
 * <p>Named without a {@code _data_driven} suffix on purpose: a factory {@code type} is a registry key
 * shared by every content file, whereas the suffix belongs to the instance ids so the data-driven
 * fixture cannot collide with the programmatic {@code kasuga_lib:test_fan_formula}.
 *
 * <p>This is the first real content use of the reload domain: its index manifest
 * ({@code data/kasuga_lib/kasuga_lib/data_driven/modelling.json}) carries both {@code on_register}
 * (the block content file) and {@code on_reload} (the animation clip file), while the state machine
 * file is found by the {@code state_machines/} directory glob.
 *
 * <p>The factories are registered from an {@code @PostConstruct} method of an {@code @Context} bean,
 * which is safe because the registration callback that consumes the JSON index
 * ({@code JsonTreeIntegration}'s lazy {@code RegisterContextRegistry} hook) runs at startup dispatch,
 * strictly after every {@code @Context} bean was constructed — the timing used by
 * {@code DataDrivenTestFactories}.
 */
@Context
public class FanDataDrivenFactories {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The data-driven fan block id (suffix distinguishes it from the programmatic fixture). */
    public static final ResourceLocation BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_fan_formula_data_driven");

    /** The block entity type id; derived by the data-driven handler as {@code <block path>_be}. */
    public static final ResourceLocation BE_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_fan_formula_data_driven_be");

    /** The data-driven machine definition id (loaded from {@code state_machines/fan_machine_data_driven.json}). */
    public static final Id MACHINE_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan_machine_data_driven");

    /** The data-driven clip id (loaded from {@code animation_clips/fan_fsm_data_driven.json}). */
    public static final Id CLIP_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm_data_driven");

    /** The factory type of the block half. */
    public static final String BLOCK_TYPE = "fan_block";

    /** The factory type of the block entity half. */
    public static final String BE_TYPE = "fan_be";

    /**
     * Registers both factories and the (pure-data) pipeline binding for the data-driven block.
     *
     * <p>The binding is registered here rather than in a separate bean because it describes exactly the
     * block these factories create; it is not a data-driven content type (no JSON shape), so it stays
     * code-side, mirroring {@code ModellingContentTest}'s registration for the programmatic fixture.
     */
    @PostConstruct
    public void init() {
        FactoryRegistry.register(BLOCK_TYPE, (id, params) -> blockWithItem(id,
                props -> new FanDataDrivenBlock(props, () -> BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BE_ID))));
        FactoryRegistry.registerBlockEntity(BE_TYPE, FanDataDrivenFactories::createFanBeReg);

        PipelineBindingRegistry.registerBlock(BLOCK_ID, BlockPipelineBinding.single(
                ModellingContentTest.TEST_FAN_FORMULA_MODEL,
                null
        ));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Block> Reg<?, Block> blockWithItem(
            String id, Function<BlockBehaviour.Properties, T> blockSupplier) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
    }

    /**
     * Builds the block entity registration, reading {@code state_machine} / {@code model} from the
     * block entity's JSON {@code params} and capturing them into the type's supplier — the same
     * contract as the built-in {@code fsm_be} factory. A missing or malformed param warns but does not
     * abort: a block entity without a machine / model still ticks its (empty) FSM state.
     */
    private static Reg<?, ?> createFanBeReg(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params) {
        Id machineId = readId(params, "state_machine");
        if (machineId == null) {
            LOGGER.warn("[fan_be] '{}' has no valid 'state_machine' param; its block entity will not run a machine", id);
        }
        ResourceLocation model = readResourceLocation(params, "model");
        if (model == null) {
            LOGGER.warn("[fan_be] '{}' has no valid 'model' param; its block entity will not load a model", id);
        }
        BlockEntityReg<FanBlockEntity> reg = new BlockEntityReg<>(id,
                r -> (pos, state) -> new FanBlockEntity(r.getEntry(), pos, state, machineId, model));
        reg.withProperty(Collection.class,
                col -> { col.addAll(Arrays.asList(validBlocks.get())); return col; });
        return reg;
    }

    // The two readers below mirror FsmBlockEntityFactories.readId/readResourceLocation. They are copied
    // instead of shared because the originals are private; promoting them would mean editing the
    // modelling main factory class, and this task keeps existing production code untouched. Both are
    // small, side-effect free, and encode the same params contract (a string primitive or null).

    @Nullable
    private static Id readId(@Nullable JsonObject params, String key) {
        if (params == null || !params.has(key) || !params.get(key).isJsonPrimitive()) {
            return null;
        }
        return Id.tryParse(params.get(key).getAsString());
    }

    @Nullable
    private static ResourceLocation readResourceLocation(@Nullable JsonObject params, String key) {
        if (params == null || !params.has(key) || !params.get(key).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(params.get(key).getAsString());
    }
}
