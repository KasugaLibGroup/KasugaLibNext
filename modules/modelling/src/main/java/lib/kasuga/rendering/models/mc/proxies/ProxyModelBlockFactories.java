package lib.kasuga.rendering.models.mc.proxies;

import com.google.gson.JsonObject;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.state.StatePropertyContext;
import lib.kasuga.registration.data_driven.state.StatePropertySet;
import lib.kasuga.registration.data_driven.state.StatefulBlockFactory;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * Built-in data-driven factories for proxy-rendered blocks.
 *
 * <p>{@code proxy_model} is a stateful block type, so it composes with JSON
 * {@code state_properties}. {@code proxy_model_be} reads the runtime model from
 * {@code params.model} and the optional archive entry from {@code params.model_name}.
 */
@Context
public final class ProxyModelBlockFactories {
    public static final String BLOCK_TYPE = "proxy_model";
    public static final String BLOCK_ENTITY_TYPE = "proxy_model_be";

    @PostConstruct
    public void init() {
        registerBuiltin();
    }

    /** Idempotent registration for both production startup and focused tests. */
    public static void registerBuiltin() {
        FactoryRegistry.register(
                BLOCK_TYPE,
                (StatefulBlockFactory) ProxyModelBlockFactories::createBlock,
                true
        );
        FactoryRegistry.registerBlockEntity(
                BLOCK_ENTITY_TYPE,
                ProxyModelBlockFactories::createBlockEntity
        );
    }

    @SuppressWarnings("unchecked")
    private static Reg<?, Block> createBlock(
            String id,
            @Nullable JsonObject params,
            StatePropertySet properties
    ) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(
                id,
                blockProperties -> StatePropertyContext.construct(
                        properties,
                        () -> new ProxyModelBlock(blockProperties)
                )
        ).withDefaultBlockItem(id);
    }

    private static Reg<?, ?> createBlockEntity(
            String id,
            Supplier<Block[]> validBlocks,
            @Nullable JsonObject params
    ) {
        ModelParams input = ModelParams.parse(params);
        if (input.model == null) {
            throw new IllegalArgumentException(BLOCK_ENTITY_TYPE + " requires params.model");
        }
        BlockEntityReg<ProxyModelBlockEntity> registration = new BlockEntityReg<>(
                id,
                reg -> (pos, state) -> new ProxyModelBlockEntity(
                        reg.getEntry(),
                        pos,
                        state,
                        input.model,
                        input.modelName
                )
        );
        registration.withProperty(Collection.class, values -> {
            values.addAll(Arrays.asList(validBlocks.get()));
            return values;
        });
        registration.onRegistered(type ->
                ProxyModelProxy.register(type, registration.getResourceLocation()));
        return registration;
    }

    private static final class ModelParams {
        private final ResourceLocation model;
        private final String modelName;

        private ModelParams(ResourceLocation model, String modelName) {
            this.model = model;
            this.modelName = modelName;
        }

        private static ModelParams parse(@Nullable JsonObject params) {
            ResourceLocation model = null;
            String modelName = null;
            if (params != null) {
                if (params.has("model") && params.get("model").isJsonPrimitive()) {
                    model = ResourceLocation.tryParse(params.get("model").getAsString());
                }
                if (params.has("model_name") && params.get("model_name").isJsonPrimitive()) {
                    modelName = params.get("model_name").getAsString();
                }
            }
            return new ModelParams(model, modelName);
        }
    }

}
