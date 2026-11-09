package test.kasuga.modelling;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
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
 * Data-driven half of the fan example: the same {@link FanBlock} / {@link FanBlockEntity} pair as the
 * code-registered {@code example_fan}, with the machine and model read from the content file's
 * {@code params} instead of being baked into the code path.
 *
 * <p>This is the fixture that proves "one implementation, two registration styles": the block entity
 * class carries no registration knowledge, and its {@code machine} / {@code model} are ordinary
 * constructor arguments.
 *
 * <p>Its index manifest ({@code data/kasuga_lib/kasuga_lib/data_driven/modelling.json}) carries
 * {@code on_register} (the block content file) and {@code on_reload} (the animation clip); the state
 * machine file is found by the {@code state_machines/} directory glob.
 */
@Context
public class FanDataDrivenFactories {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The data-driven fan block id. */
    public static final ResourceLocation BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "example_fan_data_driven");

    /** The block entity type id; derived by the data-driven handler as {@code <block path>_be}. */
    public static final ResourceLocation BE_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "example_fan_data_driven_be");

    /** The factory type of the block half. */
    public static final String BLOCK_TYPE = "example_fan_block";

    /** The factory type of the block entity half. */
    public static final String BE_TYPE = "example_fan_be";

    @PostConstruct
    public void init() {
        FactoryRegistry.register(BLOCK_TYPE, (id, params) -> blockWithItem(id,
                props -> new FanBlock(props, () -> BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BE_ID))));
        FactoryRegistry.registerBlockEntity(BE_TYPE, FanDataDrivenFactories::createFanBeReg);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Block> Reg<?, Block> blockWithItem(
            String id, Function<BlockBehaviour.Properties, T> blockSupplier) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
    }

    /**
     * Builds the block entity registration, reading {@code state_machine} / {@code model} from the
     * content file's {@code params} and capturing them into the type's supplier. A missing or malformed
     * param warns but does not abort: a block entity without a machine / model still ticks harmlessly.
     */
    private static Reg<?, ?> createFanBeReg(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params) {
        Id machineId = readId(params, "state_machine");
        if (machineId == null) {
            LOGGER.warn("[example_fan_be] '{}' has no valid 'state_machine' param; its block entity will not run a machine", id);
        }
        ResourceLocation model = readResourceLocation(params, "model");
        if (model == null) {
            LOGGER.warn("[example_fan_be] '{}' has no valid 'model' param; its block entity will not load a model", id);
        }
        BlockEntityReg<FanBlockEntity> reg = new BlockEntityReg<>(id,
                r -> (pos, state) -> new FanBlockEntity(r.getEntry(), pos, state, machineId, model));
        reg.withProperty(Collection.class,
                col -> { col.addAll(Arrays.asList(validBlocks.get())); return col; });
        return FanProxyDefinitions.registerWhenReady(reg);
    }

    // The two readers below mirror FsmBlockEntityFactories.readId/readResourceLocation. The originals are
    // private; both are small, side-effect free, and encode the same params contract (a string or null).

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
