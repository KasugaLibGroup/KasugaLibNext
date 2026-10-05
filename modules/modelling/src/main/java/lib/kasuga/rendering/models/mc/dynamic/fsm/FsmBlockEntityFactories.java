package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
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
 * Built-in data-driven factories for FSM blocks, registered in a static block (idempotent
 * last-writer-wins): {@code fsm_block} → {@link FsmBlock} with a default block item; {@code fsm_be}
 * → {@link BlockEntityReg} of {@link AnimationBlockEntity}, reading {@code state_machine} /
 * {@code model} / {@code model_name} from the params channel (injected by
 * {@code BlockEntityTypeHandler.extractEmbedded} from the block's top-level {@code state_machine}
 * field) and capturing them into the block entity supplier.
 *
 * <p>Not a Micronaut {@code @Context} bean: the static block touches core's {@link FactoryRegistry},
 * absent from the modelling-only runtime classpath; registration is triggered explicitly by
 * {@code DataDrivenTestFactories.registerBuiltin()} where core is present.
 */
public final class FsmBlockEntityFactories {

    private static final Logger LOGGER = LogUtils.getLogger();

    static {
        registerBuiltin();
    }

    private FsmBlockEntityFactories() {}

    /** Register (or re-register) the built-in factories; idempotent. */
    public static void registerBuiltin() {
        FactoryRegistry.register("fsm_block", (id, params) -> blockWithItem(id, FsmBlock::new));
        FactoryRegistry.registerBlockEntity("fsm_be", FsmBlockEntityFactories::createFsmBeReg);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Block> Reg<?, Block> blockWithItem(
            String id, Function<BlockBehaviour.Properties, T> blockSupplier) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
    }

    private static Reg<?, ?> createFsmBeReg(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params) {
        Id machineId = readId(params, "state_machine");
        if (machineId == null) {
            LOGGER.warn("[fsm_be] '{}' has no valid 'state_machine' param; its block entity will not run a machine", id);
        }
        ResourceLocation modelLoc = readResourceLocation(params, "model");
        if (modelLoc == null) {
            LOGGER.warn("[fsm_be] '{}' has no valid 'model' param; its block entity will not load a model", id);
        }
        String modelName = readString(params, "model_name");
        BlockEntityReg<AnimationBlockEntity> reg = new BlockEntityReg<>(id,
                r -> (pos, state) -> new AnimationBlockEntity(r.getEntry(), pos, state, machineId, modelLoc, modelName));
        reg.withProperty(Collection.class,
                col -> { col.addAll(Arrays.asList(validBlocks.get())); return col; });
        return reg;
    }

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

    @Nullable
    private static String readString(@Nullable JsonObject params, String key) {
        if (params == null || !params.has(key) || !params.get(key).isJsonPrimitive()) {
            return null;
        }
        return params.get(key).getAsString();
    }
}
