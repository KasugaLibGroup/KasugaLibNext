package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.json.DataJson;
import java.util.Objects;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.property.JsonItemParser;
import lib.kasuga.registration.data_driven.property.JsonPropertyParser;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.state.StatefulBlockFactory;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Function;

public class BlockTypeHandler extends RegTypeHandler<BlockDef> {

    @Override
    public String getTypeName() { return "blocks"; }

    @Override
    public int getPhase() { return PHASE_CONTENT; }

    @Override
    public BlockDef parse(JsonObject json) {
        BlockDef definition = DataJson.GSON.fromJson(json, BlockDef.class);
        Objects.requireNonNull(definition.id(), "id");
        Objects.requireNonNull(definition.type(), "type");
        return definition;
    }

    @Override
    public void apply(BlockDef definition, BuildContext context) {
        if (definition.stateProperties() != null
                && !(FactoryRegistry.get(definition.type()) instanceof StatefulBlockFactory)) {
            throw new IllegalArgumentException("Block '" + definition.id() + "' declares state_properties, but factory '"
                    + definition.type() + "' does not support them");
        }
        super.apply(definition, context);
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
        String tab = DataJson.creativeTab(definition.itemProperties());
        return tab == null ? null : ResourceLocation.parse(tab);
    }

    @Override
    public void validate(BlockDef definition, ValidationContext context) {
        super.validate(definition, context);
        if (definition.properties() != null) {
            try {
                JsonPropertyParser.getInstance().parseBlockProperties(definition.properties());
            } catch (RuntimeException failure) {
                context.error(
                        lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.CONTENT_INVALID,
                        "/properties",
                        "Invalid block properties: " + failure.getMessage(),
                        "Fix the property names and value shapes."
                );
            }
        }
        if (definition.itemProperties() != null) {
            try {
                JsonItemParser.INSTANCE.parseItemProperties(definition.itemProperties());
            } catch (RuntimeException failure) {
                context.error(
                        lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.CONTENT_INVALID,
                        "/item_properties",
                        "Invalid item properties: " + failure.getMessage(),
                        "Fix the property names and value shapes."
                );
            }
        }
        if (definition.stateProperties() != null
                && context.factoryAvailable(ValidationContext.FactoryType.BLOCK, definition.type())
                && !context.factorySupports(
                        ValidationContext.FactoryType.BLOCK,
                        definition.type(),
                        StatefulBlockFactory.class)) {
            context.error(
                    lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.UNKNOWN_FACTORY,
                    "/type",
                    "Block factory '" + definition.type() + "' does not support state_properties",
                    "Use a StatefulBlockFactory or remove state_properties."
            );
        }
    }

    @Override
    public List<lib.kasuga.registration.data_driven.pipeline.RegistryClaim> claims(
            BlockDef definition,
            ValidationContext context
    ) {
        List<lib.kasuga.registration.data_driven.pipeline.RegistryClaim> claims =
                new ArrayList<>(super.claims(definition, context));
        if (FactoryRegistry.createsBlockItem(definition.type())) {
            ResourceLocation id = ResourceLocation.tryParse(
                    context.currentDefinition().key().id()
            );
            if (id != null) {
                claims.add(context.claim("minecraft:item", id));
            }
        }
        return List.copyOf(claims);
    }

    @Override
    protected Reg<?, ?> createRegistration(BlockDef definition, String path) {
        FactoryRegistry.BlockFactory factory = FactoryRegistry.get(definition.type());
        if (factory == null) return null;
        if (definition.stateProperties() != null) {
            return ((StatefulBlockFactory) factory).create(path, definition.params(), definition.stateProperties());
        }
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
