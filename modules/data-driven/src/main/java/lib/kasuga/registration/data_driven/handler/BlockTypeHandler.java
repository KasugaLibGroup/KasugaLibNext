package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.property.JsonItemParser;
import lib.kasuga.registration.data_driven.property.JsonPropertyParser;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.List;
import java.util.function.Function;

public class BlockTypeHandler extends RegTypeHandler<BlockDef> {

    @Override
    public String getTypeName() { return "blocks"; }

    @Override
    public int getPhase() { return PHASE_CONTENT; }

    @Override
    public BlockDef parse(JsonObject json) {
        return new BlockDef(
            json.get("id").getAsString(),
            json.get("type").getAsString(),
            json.has("registry_group") ? json.get("registry_group").getAsString() : null,
            json.has("properties") ? json.getAsJsonObject("properties") : null,
            json.has("item_properties") ? json.getAsJsonObject("item_properties") : null,
            json.has("params") ? json.getAsJsonObject("params") : null
        );
    }

    @Override
    protected String resolveRawId(BlockDef definition) {
        return definition.id();
    }

    @Override
    protected String resolveType(BlockDef definition) {
        return definition.type();
    }

    @Override
    protected String resolveRegistryGroup(BlockDef definition) {
        return definition.registryGroup();
    }

    @Override
    protected ResourceLocation resolveCreativeTab(BlockDef definition) {
        if (definition.itemProperties() != null && definition.itemProperties().has("tab")) {
            return ResourceLocation.parse(definition.itemProperties().get("tab").getAsString());
        }
        return null;
    }

    @Override
    protected Reg<?, ?> createRegistration(BlockDef definition, String path) {
        FactoryRegistry.BlockFactory factory = FactoryRegistry.get(definition.type());
        if (factory == null) return null;
        return factory.create(path, definition.params());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void configureTypeSpecific(BlockDef definition, Reg<?, ?> reg) {
        if (definition.properties() != null) {
            List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> mods =
                JsonPropertyParser.getInstance().parseBlockProperties(definition.properties());
            for (Function<BlockBehaviour.Properties, BlockBehaviour.Properties> m : mods) {
                reg.withProperty(BlockBehaviour.Properties.class, m);
            }
        }

        // Parse block-level item_properties and store on the BlockReg.
        // When the child ItemReg calls applyProperties(Item.Properties.class, ...),
        // these modifiers are picked up via the parent chain and naturally
        // override group-level item properties from RegistryGroupHandler.
        if (definition.itemProperties() != null) {
            List<Function<Item.Properties, Item.Properties>> itemMods =
                JsonItemParser.INSTANCE.parseItemProperties(definition.itemProperties());
            for (Function<Item.Properties, Item.Properties> m : itemMods) {
                reg.withProperty(Item.Properties.class, m);
            }
        }
    }
}
