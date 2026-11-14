package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.json.DataJson;
import java.util.Objects;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.HandlerCapability;
import lib.kasuga.registration.data_driven.property.JsonItemParser;
import lib.kasuga.registration.data_driven.property.JsonPropertyParser;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class RegistryGroupHandler extends MetaTypeHandler<RegistryGroupDef> {

    @Override
    public String getTypeName() { return "registry_groups"; }

    @Override
    public int getPhase() { return PHASE_GROUPS; }

    @Override
    public Set<HandlerCapability> capabilities() {
        return Set.of(
                HandlerCapability.STRICT_PREFLIGHT,
                HandlerCapability.NON_REGISTRATION
        );
    }

    @Override
    public RegistryGroupDef parse(JsonObject json) {
        RegistryGroupDef definition = DataJson.GSON.fromJson(json, RegistryGroupDef.class);
        Objects.requireNonNull(definition.id(), "id");
        return definition;
    }

    @Override
    public void apply(RegistryGroupDef definition, BuildContext context) {
        store(definition, (RegBuildContext) context);
    }

    /**
     * Groups are addressed by their raw {@code id()} everywhere else in the loader (blocks look them
     * up verbatim via {@code registry_group}), so duplicate detection uses the exact same string:
     * normalizing it here would make a group unreachable from its own references.
     *
     * @param modId      the owning mod's id (unused: group ids are matched verbatim)
     * @param definition the parsed group definition
     * @return the group's {@code id()}
     */
    @Override
    public String resolveIdentity(String modId, RegistryGroupDef definition) {
        return definition.id();
    }

    @Override
    public void validate(RegistryGroupDef definition, ValidationContext context) {
        if (definition.parent() != null) {
            context.requireDefinition(
                    new DefinitionKey(getTypeName(), definition.parent()),
                    "/parent"
            );
        }
        if (definition.properties() != null) {
            try {
                JsonPropertyParser.getInstance().parseBlockProperties(definition.properties());
            } catch (RuntimeException failure) {
                context.error(
                        lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.CONTENT_INVALID,
                        "/properties",
                        "Invalid registry group block properties: " + failure.getMessage(),
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
                        "Invalid registry group item properties: " + failure.getMessage(),
                        "Fix the property names and value shapes."
                );
            }
        }
    }

    @Override
    public DefinitionKey parentDefinition(RegistryGroupDef definition) {
        return definition.parent() == null
                ? null
                : new DefinitionKey(getTypeName(), definition.parent());
    }

    @SuppressWarnings("unchecked")
    private void store(RegistryGroupDef definition, RegBuildContext context) {
        JsonRegistryGroup group = new JsonRegistryGroup(definition.id());

        if (definition.parent() != null) {
            JsonRegistryGroup parent = context.getRegistryGroup(definition.parent());
            group.setParent(parent != null ? parent : context.getRootGroup());
        } else {
            group.setParent(context.getRootGroup());
        }

        if (definition.properties() != null) {
            List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> mods =
                JsonPropertyParser.getInstance().parseBlockProperties(definition.properties());
            for (Function<BlockBehaviour.Properties, BlockBehaviour.Properties> m : mods) {
                group.withProperty(BlockBehaviour.Properties.class, m);
            }
        }

        String tab = DataJson.creativeTab(definition.itemProperties());
        if (tab != null) context.setRegistryGroupCreativeTab(definition.id(), ResourceLocation.parse(tab));

        // Parse remaining item_properties into the group's property chain
        // (tab is skipped by JsonItemParser). Block-level item_properties will
        // naturally override group-level values via Reg.applyProperties parent-first order.
        if (definition.itemProperties() != null) {
            List<Function<Item.Properties, Item.Properties>> itemMods =
                JsonItemParser.INSTANCE.parseItemProperties(definition.itemProperties());
            for (Function<Item.Properties, Item.Properties> m : itemMods) {
                group.withProperty(Item.Properties.class, m);
            }
        }

        context.putRegistryGroup(definition.id(), group);
    }
}
