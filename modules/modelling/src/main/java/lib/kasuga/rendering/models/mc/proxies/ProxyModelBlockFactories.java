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
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Built-in data-driven factories for proxy-rendered blocks.
 *
 * <p>{@code proxy_model} is a stateful block type, so it composes with JSON
 * {@code state_properties}. {@code proxy_model_be} reads the runtime model from
 * either one default model from {@code params.model} or compiled multi-part groups from
 * {@code params.model_groups}. {@code model_by_state} selects a complete group, while
 * {@code yaw_offset} and {@code facing_yaw} stay proxy-root concerns. Animation control
 * belongs to a content-specific Java composition rather than a property-to-clip map.
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
        validateBlockParams(params);
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(
                id,
                blockProperties -> StatePropertyContext.construct(
                        properties,
                        () -> new ProxyModelBlock(blockProperties)
                )
        ).withDefaultBlockItem(id);
    }

    static void validateBlockParams(@Nullable JsonObject params) {
        if (params != null && !params.isEmpty()) {
            throw new IllegalArgumentException(
                    "proxy_model does not accept block-level params; "
                            + "implement content-specific interactions in a dedicated block class and factory"
            );
        }
    }

    private static Reg<?, ?> createBlockEntity(
            String id,
            Supplier<Block[]> validBlocks,
            @Nullable JsonObject params
    ) {
        ModelParams input = ModelParams.parse(params);
        BlockEntityReg<ProxyModelBlockEntity> registration = new BlockEntityReg<>(
                id,
                reg -> (pos, state) -> new ProxyModelBlockEntity(
                        reg.getEntry(),
                        pos,
                        state,
                        input.selection,
                        input.yawOffset,
                        input.facingYaw,
                        input.lightingMode
                )
        );
        registration.withProperty(Collection.class, values -> {
            values.addAll(Arrays.asList(validBlocks.get()));
            return values;
        });
        registration.onRegistered(type -> {
            for (Block block : validBlocks.get()) {
                input.selection.validate(block.defaultBlockState());
            }
            ProxyModelProxy.register(type, registration.getResourceLocation());
        });
        return registration;
    }

    /**
     * Public registration-time model configuration for consuming-mod proxy-model hosts.
     */
    public record ModelSettings(
            ProxyModelSelection selection,
            float yawOffset,
            Map<String, Float> facingYaw,
            @Nullable ModelLightingMode lightingMode
    ) {
        public ModelSettings(ProxyModelSelection selection, float yawOffset, Map<String, Float> facingYaw) {
            this(selection, yawOffset, facingYaw, null);
        }
    }

    public static ModelSettings parseModelSettings(@Nullable JsonObject params) {
        ModelParams parsed = ModelParams.parse(params);
        return new ModelSettings(parsed.selection, parsed.yawOffset, parsed.facingYaw, parsed.lightingMode);
    }

    static final class ModelParams {
        final ProxyModelSelection selection;
        final float yawOffset;
        final Map<String, Float> facingYaw;
        @Nullable
        final ModelLightingMode lightingMode;

        private ModelParams(
                ProxyModelSelection selection,
                float yawOffset,
                Map<String, Float> facingYaw,
                @Nullable ModelLightingMode lightingMode
        ) {
            this.selection = selection;
            this.yawOffset = yawOffset;
            this.facingYaw = facingYaw;
            this.lightingMode = lightingMode;
        }

        static ModelParams parse(@Nullable JsonObject params) {
            String model = null;
            String modelName = null;
            String defaultModel = null;
            float yawOffset = 0f;
            ModelLightingMode lightingMode = null;
            Map<String, Float> facingYaw = Map.of();
            Map<String, CompiledModelGroup> groups = new LinkedHashMap<>();
            JsonObject modelByState = new JsonObject();
            if (params != null) {
                if (params.has("lighting_mode")) {
                    var value = params.get("lighting_mode");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("lighting_mode must be 'pbr' or 'vanilla_block'");
                    }
                    lightingMode = switch (value.getAsString()) {
                        case "pbr" -> ModelLightingMode.PBR;
                        case "vanilla_block" -> ModelLightingMode.BLOCK;
                        default -> throw new IllegalArgumentException(
                                "unknown lighting_mode '" + value.getAsString() + "'; expected 'pbr' or 'vanilla_block'");
                    };
                }
                if (params.has("animation")) {
                    throw new IllegalArgumentException(
                            "proxy_model_be.params.animation is not supported; "
                                    + "implement state-driven playback in a content-specific Java composition"
                    );
                }
                if (params.has("model") && params.get("model").isJsonPrimitive()) {
                    model = params.get("model").getAsString();
                }
                if (params.has("model_name") && params.get("model_name").isJsonPrimitive()) {
                    modelName = params.get("model_name").getAsString();
                }
                if (params.has("default_model") && params.get("default_model").isJsonPrimitive()) {
                    defaultModel = params.get("default_model").getAsString();
                }
                if (params.has("yaw_offset") && params.get("yaw_offset").isJsonPrimitive()) {
                    yawOffset = params.get("yaw_offset").getAsFloat();
                }
                if (params.has("facing_yaw") && params.get("facing_yaw").isJsonObject()) {
                    Map<String, Float> parsed = new LinkedHashMap<>();
                    for (var entry : params.getAsJsonObject("facing_yaw").entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            parsed.put(entry.getKey(), entry.getValue().getAsFloat());
                        }
                    }
                    facingYaw = Map.copyOf(parsed);
                }
                if (params.has("model_groups")) {
                    groups.putAll(parseModelGroups(params.getAsJsonObject("model_groups")));
                }
                if (params.has("model_by_state")) {
                    modelByState = params.getAsJsonObject("model_by_state");
                }
            }
            CompiledModelGroup defaultGroup =
                    parseDefaultGroup(model, modelName, defaultModel, groups);
            groups.putIfAbsent(defaultGroup.id(), defaultGroup);
            List<ProxyModelSelection.Rule> rules =
                    parseSelectionRules(modelByState, groups);
            ProxyModelSelection selection =
                    new ProxyModelSelection(defaultGroup, groups, rules);
            return new ModelParams(selection, yawOffset, facingYaw, lightingMode);
        }
    }

    private static CompiledModelGroup parseDefaultGroup(
            @Nullable String model,
            @Nullable String modelName,
            @Nullable String defaultModel,
            Map<String, CompiledModelGroup> groups
    ) {
        if (defaultModel != null) {
            CompiledModelGroup selected = groups.get(defaultModel);
            if (selected == null) {
                throw new IllegalArgumentException(
                        "default_model references unknown model group '" + defaultModel + "'");
            }
            return selected;
        }
        if (model != null) {
            ResourceLocation location = requireLocation(model, "model");
            ModelPart part = new ModelPart(
                    location, modelName, new Transform(), null);
            return CompiledModelGroup.single("default", part);
        }
        if (!groups.isEmpty()) {
            return groups.values().iterator().next();
        }
        throw new IllegalArgumentException(BLOCK_ENTITY_TYPE + " requires params.model or params.model_groups");
    }

    private static Map<String, CompiledModelGroup> parseModelGroups(JsonObject input) {
        if (!input.isJsonObject()) {
            throw new IllegalArgumentException("model_groups must be an object");
        }
        Map<String, CompiledModelGroup> parsed = new LinkedHashMap<>();
        for (var groupEntry : input.entrySet()) {
            if (!groupEntry.getValue().isJsonObject()) {
                throw new IllegalArgumentException(
                        "model_groups." + groupEntry.getKey() + " must be an object");
            }
            JsonObject groupObject = groupEntry.getValue().getAsJsonObject();
            float yOffset = groupObject.has("y_offset")
                    ? groupObject.get("y_offset").getAsFloat()
                    : 0f;
            if (!groupObject.has("parts") || !groupObject.get("parts").isJsonArray()) {
                throw new IllegalArgumentException(
                        "model_groups." + groupEntry.getKey() + ".parts must be an array");
            }
            List<CompiledModelPart> parts = new ArrayList<>();
            for (var partEntry : groupObject.getAsJsonArray("parts")) {
                if (!partEntry.isJsonObject()) {
                    throw new IllegalArgumentException("model group parts must be objects");
                }
                parts.add(parsePart(partEntry.getAsJsonObject()));
            }
            CompiledModelGroup group = new CompiledModelGroup(groupEntry.getKey(), yOffset, parts);
            parsed.put(group.id(), group);
        }
        return parsed;
    }

    private static CompiledModelPart parsePart(JsonObject input) {
        String id = requiredString(input, "id", "model group part");
        ResourceLocation model = requireLocation(
                requiredString(input, "model", "model group part"), "model group part.model");
        String modelName = input.has("model_name") && input.get("model_name").isJsonPrimitive()
                ? input.get("model_name").getAsString()
                : null;
        Transform local = parseLocal(input);
        return new CompiledModelPart(id, model, modelName, local);
    }

    private static Transform parseLocal(JsonObject input) {
        Transform transform = new Transform();
        if (!input.has("local")) {
            return transform;
        }
        if (!input.get("local").isJsonArray()) {
            throw new IllegalArgumentException("model group part.local must be an array");
        }
        for (var operationEntry : input.getAsJsonArray("local")) {
            if (!operationEntry.isJsonObject()) {
                throw new IllegalArgumentException("model group local operations must be objects");
            }
            JsonObject operation = operationEntry.getAsJsonObject();
            int present = (operation.has("translate") ? 1 : 0)
                    + (operation.has("rotate") ? 1 : 0)
                    + (operation.has("scale") ? 1 : 0);
            if (present != 1) {
                throw new IllegalArgumentException(
                        "model group local operation must contain exactly one of "
                                + "translate, rotate, or scale");
            }
            if (operation.has("translate")) {
                float[] value = vector(operation, "translate");
                transform.translate(value[0], value[1], value[2]);
            } else if (operation.has("rotate")) {
                float[] value = vector(operation, "rotate");
                transform.rotate(value[0], value[1], value[2], true);
            } else {
                float[] value = vector(operation, "scale");
                transform.scale(value[0], value[1], value[2]);
            }
        }
        return transform;
    }

    private static float[] vector(JsonObject input, String field) {
        if (!input.get(field).isJsonArray() || input.getAsJsonArray(field).size() != 3) {
            throw new IllegalArgumentException("local." + field + " must contain three numbers");
        }
        float[] values = new float[3];
        for (int index = 0; index < 3; index++) {
            values[index] = input.getAsJsonArray(field).get(index).getAsFloat();
            if (!Float.isFinite(values[index])) {
                throw new IllegalArgumentException("local." + field + " must be finite");
            }
        }
        return values;
    }

    private static List<ProxyModelSelection.Rule> parseSelectionRules(
            JsonObject input,
            Map<String, CompiledModelGroup> groups
    ) {
        List<ProxyModelSelection.Rule> parsed = new ArrayList<>();
        for (var propertyEntry : input.entrySet()) {
            if (!propertyEntry.getValue().isJsonObject()) {
                throw new IllegalArgumentException(
                        "model_by_state." + propertyEntry.getKey() + " must be an object"
                );
            }
            Map<String, CompiledModelGroup> values = new LinkedHashMap<>();
            for (var valueEntry : propertyEntry.getValue().getAsJsonObject().entrySet()) {
                if (!valueEntry.getValue().isJsonPrimitive()) {
                    throw new IllegalArgumentException(
                            "model_by_state values must be group ids or model locations");
                }
                String target = valueEntry.getValue().getAsString();
                CompiledModelGroup group = groups.get(target);
                if (group == null) {
                    ResourceLocation model = ResourceLocation.tryParse(target);
                    if (model == null) {
                        throw new IllegalArgumentException(
                                "model_by_state." + propertyEntry.getKey() + "."
                                        + valueEntry.getKey()
                                        + " references unknown group or model '" + target + "'");
                    }
                    String groupId = "legacy_" + model.getNamespace() + "_"
                            + model.getPath().replace('/', '_');
                    group = CompiledModelGroup.single(
                            groupId,
                            new ModelPart(model, null, new Transform(), null)
                    );
                    groups.put(groupId, group);
                }
                values.put(valueEntry.getKey(), group);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "model_by_state." + propertyEntry.getKey() + " must not be empty"
                );
            }
            parsed.add(new ProxyModelSelection.Rule(
                    propertyEntry.getKey(), Collections.unmodifiableMap(values)));
        }
        return List.copyOf(parsed);
    }

    private static ResourceLocation requireLocation(String value, String path) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new IllegalArgumentException(path + " is not a valid model location");
        }
        return location;
    }

    private static String requiredString(JsonObject input, String key, String path) {
        if (!input.has(key) || !input.get(key).isJsonPrimitive()
                || input.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException(path + "." + key + " must be a non-blank string");
        }
        return input.get(key).getAsString();
    }

}
