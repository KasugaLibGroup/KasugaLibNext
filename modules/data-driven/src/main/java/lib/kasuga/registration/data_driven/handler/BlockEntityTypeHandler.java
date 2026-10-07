package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.dedup.EffectiveId;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.List;

public class BlockEntityTypeHandler implements TypeHandler<BlockEntityDef> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String getTypeName() { return "block_entities"; }

    @Override
    public int getPhase() { return PHASE_EMBEDDED; }

    @Override
    public String getParentTypeName() { return "blocks"; }

    @Override
    public String getEmbeddedKeyName() { return "block_entity"; }

    @Override
    public List<JsonObject> extractEmbedded(JsonObject blockJson) {
        if (!blockJson.has("block_entity")) return null;
        JsonObject be = blockJson.getAsJsonObject("block_entity").deepCopy();
        be.addProperty("_parent_block", blockJson.get("id").getAsString());
        // Top-level state_machine is forwarded to the block entity factory via params (like model/model_name); non-string values are ignored.
        if (blockJson.has("state_machine") && blockJson.get("state_machine").isJsonPrimitive()) {
            JsonObject params = be.has("params") ? be.getAsJsonObject("params") : new JsonObject();
            params.addProperty("state_machine", blockJson.get("state_machine").getAsString());
            be.add("params", params);
        }
        return List.of(be);
    }

    @Override
    public BlockEntityDef parse(JsonObject json) {
        return new BlockEntityDef(
            json.get("type").getAsString(),
            json.get("_parent_block").getAsString(),
            json.has("params") ? json.getAsJsonObject("params") : null
        );
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
        return EffectiveId.of(modId, definition.parentBlockId());
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
        } catch (Exception e) {
            LOGGER.warn("Failed to apply block entity for '{}': {}",
                parentBlockId, e.getMessage());
            LOGGER.debug("Full stack trace", e);
        }
    }
}
