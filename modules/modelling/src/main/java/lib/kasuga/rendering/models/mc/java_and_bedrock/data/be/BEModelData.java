package lib.kasuga.rendering.models.mc.java_and_bedrock.data.be;

import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.mc.component.ModelComponents;
import lib.kasuga.rendering.models.mc.component.ModelComponentsHolder;
import lombok.Getter;
import org.joml.Vector3f;

@Getter
public class BEModelData implements ModelData, ModelComponentsHolder {

    private final String identifier;
    private final String formatVersion;
    private final float textureWidth;
    private final float textureHeight;
    private final float visibleBoundsWidth;
    private final float visibleBoundsHeight;
    private final Vector3f visibleBoundsOffset;
    private final boolean legacy;
    private final ModelComponents components;

    public BEModelData(String identifier, String formatVersion, float textureWidth, float textureHeight,
                       float visibleBoundsWidth, float visibleBoundsHeight, Vector3f visibleBoundsOffset, boolean legacy) {
        this(identifier, formatVersion, textureWidth, textureHeight, visibleBoundsWidth, visibleBoundsHeight,
                visibleBoundsOffset, legacy, null);
    }

    public BEModelData(String identifier, String formatVersion,
                       float textureWidth, float textureHeight,
                       float visibleBoundsWidth, float visibleBoundsHeight,
                       Vector3f visibleBoundsOffset,
                       boolean legacy, ModelComponents components) {
        this.identifier = identifier;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.visibleBoundsWidth = visibleBoundsWidth;
        this.visibleBoundsHeight = visibleBoundsHeight;
        this.visibleBoundsOffset = visibleBoundsOffset;
        this.formatVersion = formatVersion;
        this.legacy = legacy;
        this.components = components;
    }

    @Override
    public boolean isMeshTriangles() {
        return false;
    }

    @Override
    public ModelComponents components() { return components; }
}
