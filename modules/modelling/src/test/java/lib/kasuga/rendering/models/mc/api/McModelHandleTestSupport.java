package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.resources.ResourceLocation;

/** Test-only access to the package-private custom-handle seam. */
public final class McModelHandleTestSupport {

    private McModelHandleTestSupport() {
    }

    public static McModelHandle custom(ResourceLocation modelLoc, ResourceLocation instanceLoc,
                                       ModelInstance instance) {
        return McModelHandle.custom(modelLoc, null, instanceLoc, ignored -> instance, ignored -> null);
    }
}
