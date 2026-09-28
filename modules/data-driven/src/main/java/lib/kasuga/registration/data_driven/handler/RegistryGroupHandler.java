package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.property.JsonItemParser;
import lib.kasuga.registration.data_driven.property.JsonPropertyParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.List;
import java.util.function.Function;

public class RegistryGroupHandler extends MetaTypeHandler<RegistryGroupDef> {

    @Override
    public String getTypeName() { return "registry_groups"; }

    @Override
    public int getPhase() { return PHASE_GROUPS; }

    @Override
    public RegistryGroupDef parse(JsonObject json) {
        return new RegistryGroupDef(
            json.get("id").getAsString(),
            json.has("parent") ? json.get("parent").getAsString() : null,
            json.has("properties") ? json.getAsJsonObject("properties") : null,
            json.has("item_properties") ? json.getAsJsonObject("item_properties") : null
        );
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

        if (definition.itemProperties() != null && definition.itemProperties().has("tab")) {
            String tabStr = definition.itemProperties().get("tab").getAsString();
            context.setRegistryGroupCreativeTab(definition.id(), ResourceLocation.parse(tabStr));
        }

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
