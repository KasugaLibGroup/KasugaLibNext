package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.property.JsonItemParser;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.function.Function;

public class ItemTypeHandler extends RegTypeHandler<ItemDef> {

    @Override
    public String getTypeName() { return "items"; }

    @Override
    public int getPhase() { return PHASE_CONTENT; }

    @Override
    public ItemDef parse(JsonObject json) {
        return new ItemDef(
            json.get("id").getAsString(),
            json.get("type").getAsString(),
            json.has("registry_group") ? json.get("registry_group").getAsString() : null,
            json.has("properties") ? json.getAsJsonObject("properties") : null,
            json.has("params") ? json.getAsJsonObject("params") : null
        );
    }

    @Override
    protected String resolveRawId(ItemDef definition) {
        return definition.id();
    }

    @Override
    protected String resolveType(ItemDef definition) {
        return definition.type();
    }

    @Override
    protected String resolveRegistryGroup(ItemDef definition) {
        return definition.registryGroup();
    }

    @Override
    protected ResourceLocation resolveCreativeTab(ItemDef definition) {
        if (definition.properties() != null && definition.properties().has("tab")) {
            return ResourceLocation.parse(definition.properties().get("tab").getAsString());
        }
        return null;
    }

    @Override
    protected Reg<?, ?> createRegistration(ItemDef definition, String path) {
        FactoryRegistry.ItemFactory factory = FactoryRegistry.getItemFactory(definition.type());
        if (factory == null) return null;
        return factory.create(path, definition.params());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void configureTypeSpecific(ItemDef definition, Reg<?, ?> reg) {
        if (definition.properties() != null) {
            List<Function<Item.Properties, Item.Properties>> mods =
                JsonItemParser.INSTANCE.parseItemProperties(definition.properties());
            for (Function<Item.Properties, Item.Properties> m : mods) {
                reg.withProperty(Item.Properties.class, m);
            }
        }
    }
}
