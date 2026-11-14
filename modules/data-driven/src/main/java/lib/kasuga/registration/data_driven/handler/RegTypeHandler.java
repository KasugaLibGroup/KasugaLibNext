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
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.HandlerCapability;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaim;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

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

    protected ValidationContext.FactoryType factoryType() {
        return ValidationContext.FactoryType.BLOCK;
    }

    protected String claimRegistry(T definition) {
        return switch (getTypeName()) {
            case "blocks" -> "minecraft:block";
            case "items" -> "minecraft:item";
            default -> null;
        };
    }

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
        return EffectiveId.ofLegacy(modId, resolveRawId(definition));
    }

    @Override
    public String resolveIdentity(String modId, T definition, boolean strict) {
        return strict
                ? EffectiveId.of(modId, resolveRawId(definition))
                : resolveIdentity(modId, definition);
    }

    @Override
    public Set<HandlerCapability> capabilities() {
        return Set.of(
                HandlerCapability.STRICT_PREFLIGHT,
                HandlerCapability.REGISTRY_CLAIMS
        );
    }

    @Override
    public void validate(T definition, ValidationContext context) {
        String rawId = resolveRawId(definition);
        int namespaceSeparator = rawId == null ? -1 : rawId.indexOf(':');
        if (rawId == null || namespaceSeparator <= 0
                || namespaceSeparator == rawId.length() - 1
                || ResourceLocation.tryParse(rawId) == null) {
            context.error(
                    lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.INVALID_IDENTITY,
                    "/id",
                    "Id '" + rawId + "' must be an explicit, valid namespace-qualified resource location",
                    "Write the id as '<namespace>:<path>'."
            );
        }

        String factory = resolveType(definition);
        if (factory == null || factory.isBlank()
                || !context.factoryAvailable(factoryType(), factory)) {
            context.error(
                    lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.UNKNOWN_FACTORY,
                    "/type",
                    "No " + factoryType() + " factory is registered as '" + factory + "'",
                    "Register the factory before starting strict preparation."
            );
        }

        String registryGroup = resolveRegistryGroup(definition);
        DefinitionKey groupKey = registryGroup == null
                ? null
                : new DefinitionKey("registry_groups", registryGroup);
        if (groupKey != null && context.find(groupKey).isEmpty()) {
            context.error(
                    lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code.UNKNOWN_REGISTRY_GROUP,
                    "/registry_group",
                    "Unknown registry group '" + registryGroup + "'",
                    "Define the group in registry_groups or remove registry_group."
            );
        } else if (groupKey != null) {
            context.requireDefinition(groupKey, "/registry_group");
        }
    }

    @Override
    public List<RegistryClaim> claims(T definition, ValidationContext context) {
        String registry = claimRegistry(definition);
        if (registry == null) return List.of();
        ResourceLocation id = ResourceLocation.tryParse(
                context.currentDefinition().key().id()
        );
        if (id == null) return List.of();
        return List.of(context.claim(registry, id));
    }

    @Override
    public void apply(T definition, BuildContext baseContext) {
        RegBuildContext context = (RegBuildContext) baseContext;
        try {
            String rawId = resolveRawId(definition);
            String path = resolvePath(rawId);
            String namespace = resolveNamespace(rawId);

            Reg<?, ?> reg = createRegistration(definition, path);
            if (reg == null) {
                LOGGER.warn("No factory for type '{}' (id '{}')", resolveType(definition), rawId);
                return;
            }

            if (rawId.indexOf(':') >= 0 && (context.isStrict() || !namespace.equals("minecraft"))) {
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
        } catch (RuntimeException e) {
            if (context.isStrict()) throw e;
            LOGGER.warn("Failed to apply registration for '{}': {}",
                resolveRawId(definition), e.getMessage());
            LOGGER.debug("Full stack trace", e);
        }
    }
}
