package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.json.DataJson;
import java.util.Objects;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.property.JsonItemParser;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
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
    protected ValidationContext.FactoryType factoryType() {
        return ValidationContext.FactoryType.ITEM;
    }

    @Override
    public ItemDef parse(JsonObject json) {
        ItemDef definition = DataJson.GSON.fromJson(json, ItemDef.class);
        Objects.requireNonNull(definition.id(), "id");
        Objects.requireNonNull(definition.type(), "type");
        return definition;
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
        String tab = DataJson.creativeTab(definition.properties());
        return tab == null ? null : ResourceLocation.parse(tab);
    }

    @Override
    public void validate(ItemDef definition, ValidationContext context) {
        super.validate(definition, context);
        if (definition.properties() != null) {
            try {
                JsonItemParser.INSTANCE.parseItemProperties(definition.properties());
            } catch (RuntimeException failure) {
                context.error(
                        lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.CONTENT_INVALID,
                        "/properties",
                        "Invalid item properties: " + failure.getMessage(),
                        "Fix the property names and value shapes."
                );
            }
        }
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
