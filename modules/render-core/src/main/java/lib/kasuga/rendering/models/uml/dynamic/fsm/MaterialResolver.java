package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Resolves a frame target's material ref to a concrete {@link Material}. {@link Material} has no name
 * field, so the default resolves integer refs; owners with named materials supply a custom resolver.
 */
@FunctionalInterface
public interface MaterialResolver {

    Logger LOGGER = LogUtils.getLogger();

    @Nullable Material resolve(Object ref);

    static MaterialResolver forInstance(ModelInstance model) {
        MaterialSetInstance set = model.getMaterialInstance();
        final Material[] materials = set != null ? set.getMaterials().getMaterials() : new Material[0];
        return ref -> {
            if (ref == null) {
                return null;
            }
            if (ref instanceof Number n) {
                int i = n.intValue();
                if (i >= 0 && i < materials.length) {
                    return materials[i];
                }
            }
            try {
                int i = Integer.parseInt(String.valueOf(ref));
                if (i >= 0 && i < materials.length) {
                    return materials[i];
                }
            } catch (NumberFormatException ignored) {
            }
            // debug (not warn): named materials legitimately fall through to a host-supplied custom resolver
            LOGGER.debug("FSM material ref '{}' not numeric / not resolved; returning null", ref);
            return null;
        };
    }
}
