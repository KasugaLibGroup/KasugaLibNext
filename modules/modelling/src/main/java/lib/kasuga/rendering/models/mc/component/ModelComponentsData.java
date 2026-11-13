package lib.kasuga.rendering.models.mc.component;

import lib.kasuga.rendering.models.uml.structure.data.ModelData;

/** ModelData adapter for formats whose existing data class cannot carry the extension. */
public record ModelComponentsData(ModelComponents components) implements ModelData, ModelComponentsHolder {
    public ModelComponentsData {
        if (components == null || components.isEmpty()) throw new IllegalArgumentException("components");
    }

    @Override
    public boolean isMeshTriangles() {
        return false;
    }
}
