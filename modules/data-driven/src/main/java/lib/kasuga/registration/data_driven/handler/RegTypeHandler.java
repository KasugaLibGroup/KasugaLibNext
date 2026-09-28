package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.CreativeTabModifiers;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.dedup.EffectiveId;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public abstract class RegTypeHandler<T> implements TypeHandler<T> {

    private static final Logger LOGGER = LogUtils.getLogger();

    protected abstract String resolveRawId(T definition);

    protected abstract Reg<?, ?> createRegistration(T definition, String path);

    /**
     * The factory type carried by the JSON object definition (e.g. {@code "simple_block"}),
     * as opposed to {@link #getTypeName()} which is the top-level field name (e.g. {@code "blocks"}).
     * Used in diagnostics so log messages name the actual {@code type} value.
     */
    protected String resolveType(T definition) { return getTypeName(); }

    protected void configureTypeSpecific(T definition, Reg<?, ?> reg) {}

    protected String resolveRegistryGroup(T definition) { return null; }

    protected JsonObject resolveItemProperties(T definition) { return null; }

    protected ResourceLocation resolveCreativeTab(T definition) { return null; }

    protected String resolveNamespace(String rawId) {
        String[] parts = rawId.split(":", 2);
        return parts.length > 1 ? parts[0] : "minecraft";
    }

    protected String resolvePath(String rawId) {
        String[] parts = rawId.split(":", 2);
        return parts.length > 1 ? parts[1] : parts[0];
    }

    /**
     * Identity used for duplicate resolution: the raw id normalized to the namespace it will actually
     * register under (see {@link EffectiveId}). This makes {@code "foo"} and {@code "minecraft:foo"}
     * collide as they should, instead of silently registering the same location twice.
     *
     * @param modId      the owning mod's id, used for ids without their own namespace
     * @param definition the parsed block or item definition
     * @return the effective id of {@code definition}
     */
    @Override
    public String resolveIdentity(String modId, T definition) {
        return EffectiveId.of(modId, resolveRawId(definition));
    }

    @Override
    public void apply(T definition, BuildContext baseContext) {
        try {
            RegBuildContext context = (RegBuildContext) baseContext;
            String rawId = resolveRawId(definition);
            String path = resolvePath(rawId);
            String namespace = resolveNamespace(rawId);

            Reg<?, ?> reg = createRegistration(definition, path);
            if (reg == null) {
                LOGGER.warn("No factory for type '{}' (id '{}')", resolveType(definition), rawId);
                return;
            }

            if (!namespace.equals("minecraft")) {
                reg.withProperty(ResourceLocation.class,
                    loc -> ResourceLocation.fromNamespaceAndPath(namespace, loc.getPath()));
            }

            String registryGroupId = resolveRegistryGroup(definition);
            if (registryGroupId != null) {
                JsonRegistryGroup group = context.getRegistryGroup(registryGroupId);
                reg.setParent(group != null ? group : context.getRootGroup());
            } else {
                reg.setParent(context.getRootGroup());
            }

            ResourceLocation tab = resolveCreativeTab(definition);
            if (tab != null) {
                reg.configure(CreativeTabModifiers.set(() -> tab));
            } else if (registryGroupId != null) {
                ResourceLocation groupTab = context.getRegistryGroupCreativeTab(registryGroupId);
                if (groupTab != null) {
                    reg.configure(CreativeTabModifiers.set(() -> groupTab));
                }
            }

            configureTypeSpecific(definition, reg);

            if (context.getReg(getTypeName(), rawId) != null) {
                LOGGER.warn("Duplicate id '{}' in field '{}' reached the apply phase; the loader should "
                        + "already have resolved it, so a registration was likely built directly instead "
                        + "of through the data-driven loader", rawId, getTypeName());
            }
            context.putReg(getTypeName(), rawId, reg);
        } catch (Exception e) {
            LOGGER.warn("Failed to apply registration for '{}': {}",
                resolveRawId(definition), e.getMessage());
            LOGGER.debug("Full stack trace", e);
        }
    }
}
