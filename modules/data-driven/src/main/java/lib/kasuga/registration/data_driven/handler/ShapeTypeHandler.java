package lib.kasuga.registration.data_driven.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.pipeline.ArtifactPreparationContext;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.HandlerCapability;
import lib.kasuga.registration.data_driven.pipeline.LocatedDefinition;
import lib.kasuga.registration.data_driven.shape.ShapeCatalog;
import lib.kasuga.registration.data_driven.shape.ShapeDefinition;
import lib.kasuga.registration.data_driven.shape.ShapeDomainCompiler;
import lib.kasuga.registration.data_driven.validation.ValidationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-level {@code shapes} domain. Compilation happens during prepare and apply only publishes the
 * immutable catalog through the build context.
 */
public final class ShapeTypeHandler implements TypeHandler<ShapeDefinition> {
    @Override
    public String getTypeName() {
        return ShapeDomainCompiler.TYPE_NAME;
    }

    @Override
    public int getPhase() {
        return -1;
    }

    @Override
    public ShapeDefinition parse(JsonObject json) {
        return new ShapeDefinition(json);
    }

    @Override
    public void apply(ShapeDefinition definition, BuildContext context) {
        // Artifacts were already compiled as one immutable domain before apply.
    }

    @Override
    public Set<HandlerCapability> capabilities() {
        return Set.of(HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.NON_REGISTRATION);
    }

    @Override
    public boolean strictValidationImplemented() {
        return true;
    }

    @Override
    public void validate(ShapeDefinition definition, ValidationContext context) {
        // The domain compiler needs the complete selected definition set and reports through the
        // per-definition validation contexts in prepareArtifacts.
    }

    @Override
    public Map<Class<?>, ?> prepareArtifacts(
            List<? extends LocatedDefinition<?>> definitions,
            ArtifactPreparationContext context
    ) {
        if (definitions.isEmpty()) return Map.of();
        return Map.of(ShapeCatalog.class,
                ShapeDomainCompiler.compileStrict(definitions, context));
    }

    @Override
    public String resolveIdentity(String modId, ShapeDefinition definition) {
        return stringMember(definition, "id");
    }

    @Override
    public String resolveIdentity(String modId, ShapeDefinition definition, boolean strict) {
        return resolveIdentity(modId, definition);
    }

    @Override
    public DefinitionKey parentDefinition(ShapeDefinition definition) {
        String parent = stringMember(definition, "parent");
        return parent == null ? null : new DefinitionKey(getTypeName(), parent);
    }

    private static String stringMember(ShapeDefinition definition, String name) {
        JsonElement value = definition.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String result = value.getAsString();
        return result.isBlank() ? null : result;
    }
}
