package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.json.DataJson;
import java.util.Objects;
import com.mojang.logging.LogUtils;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.dedup.EffectiveId;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.HandlerCapability;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaim;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

public class BlockEntityTypeHandler implements TypeHandler<BlockEntityDef> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String getTypeName() { return "block_entities"; }

    @Override
    public int getPhase() { return PHASE_EMBEDDED; }

    @Override
    public Set<HandlerCapability> capabilities() {
        return Set.of(
                HandlerCapability.STRICT_PREFLIGHT,
                HandlerCapability.REGISTRY_CLAIMS
        );
    }

    @Override
    public String getParentTypeName() { return "blocks"; }

    @Override
    public String getEmbeddedKeyName() { return "block_entity"; }

    @Override
    public List<JsonObject> extractEmbedded(JsonObject blockJson) {
        EmbeddedInput input = DataJson.GSON.fromJson(blockJson, EmbeddedInput.class);
        if (input.blockEntity == null) return null;
        JsonObject be = input.blockEntity.deepCopy();
        be.addProperty("_parent_block", Objects.requireNonNull(input.id, "id"));
        if (input.stateMachine != null && input.stateMachine.isJsonPrimitive()) {
            ParamsInput definition = DataJson.GSON.fromJson(be, ParamsInput.class);
            JsonObject params = definition.params == null ? new JsonObject() : definition.params;
            params.addProperty("state_machine", DataJson.GSON.fromJson(input.stateMachine, String.class));
            be.add("params", params);
        }
        return List.of(be);
    }

    @Override
    public BlockEntityDef parse(JsonObject json) {
        BlockEntityDef definition = DataJson.GSON.fromJson(json, BlockEntityDef.class);
        Objects.requireNonNull(definition.beType(), "beType");
        Objects.requireNonNull(definition.parentBlockId(), "parentBlockId");
        return definition;
    }

    /**
     * A block entity has no id of its own in JSON: the loader keys it by the host block's id, so two
     * block-entity entries sharing a parent block are duplicates. The parent id is normalized with
     * {@link EffectiveId} so an explicit {@code minecraft:} prefix collides with a bare id, exactly as
     * it does for the parent block itself.
     *
     * @param modId      the owning mod's id, used for parent ids without their own namespace
     * @param definition the parsed block-entity definition
     * @return the effective id of the host block
     */
    @Override
    public String resolveIdentity(String modId, BlockEntityDef definition) {
        return EffectiveId.ofLegacy(modId, definition.parentBlockId());
    }

    @Override
    public String resolveIdentity(String modId, BlockEntityDef definition, boolean strict) {
        return strict
                ? EffectiveId.of(modId, definition.parentBlockId())
                : resolveIdentity(modId, definition);
    }

    @Override
    public void validate(BlockEntityDef definition, ValidationContext context) {
        String parent = definition.parentBlockId();
        if (parent.indexOf(':') <= 0 || ResourceLocation.tryParse(parent) == null) {
            context.error(
                    lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.INVALID_IDENTITY,
                    "/id",
                    "Parent block id '" + parent + "' must be an explicit namespace-qualified resource location",
                    "Write the parent block id as '<namespace>:<path>'."
            );
        }
        context.requireDefinition(
                new DefinitionKey("blocks", EffectiveId.of(context.hostModId(), parent)),
                ""
        );
        if (!context.factoryAvailable(ValidationContext.FactoryType.BLOCK_ENTITY, definition.beType())) {
            context.error(
                    lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.UNKNOWN_FACTORY,
                    "/type",
                    "No block entity factory is registered as '" + definition.beType() + "'",
                    "Register the block entity factory before starting strict preparation."
            );
        }
    }

    @Override
    public List<RegistryClaim> claims(BlockEntityDef definition, ValidationContext context) {
        ResourceLocation parent = ResourceLocation.tryParse(
                EffectiveId.of(context.hostModId(), definition.parentBlockId())
        );
        if (parent == null) return List.of();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                parent.getNamespace(),
                parent.getPath() + "_be"
        );
        return List.of(context.claim("minecraft:block_entity_type", id));
    }

    @Override
    public void apply(BlockEntityDef definition, BuildContext baseContext) {
        RegBuildContext context = (RegBuildContext) baseContext;
        String parentBlockId = definition.parentBlockId();

        Reg<?, Block> blockReg = context.getBlockReg(parentBlockId);
        if (blockReg == null) {
            LOGGER.warn("Block '{}' not found for block entity association", parentBlockId);
            return;
        }

        FactoryRegistry.BlockEntityFactory factory = FactoryRegistry.getBlockEntityFactory(definition.beType());
        if (factory == null) {
            LOGGER.warn("Unknown block entity type '{}' for block '{}', registered types: {}",
                definition.beType(), parentBlockId, FactoryRegistry.getBlockEntityTypes());
            return;
        }

        String[] parts = parentBlockId.split(":", 2);
        String beName = parts.length > 1 ? parts[1] + "_be" : parentBlockId + "_be";

        try {
            Reg<?, ?> beReg = factory.create(beName, () -> new Block[]{blockReg.getEntry()}, definition.params());
            blockReg.addChild(beReg);
            context.putReg(getTypeName(), parentBlockId, beReg);
            LOGGER.debug("Attached block entity '{}' to block '{}'", beName, parentBlockId);
        } catch (RuntimeException e) {
            if (context.isStrict()) throw e;
            LOGGER.warn("Failed to apply block entity for '{}': {}",
                parentBlockId, e.getMessage());
            LOGGER.debug("Full stack trace", e);
        }
    }
    private static final class EmbeddedInput {
        String id;
        JsonObject blockEntity;
        com.google.gson.JsonElement stateMachine;
    }
    private static final class ParamsInput { JsonObject params; }
}
