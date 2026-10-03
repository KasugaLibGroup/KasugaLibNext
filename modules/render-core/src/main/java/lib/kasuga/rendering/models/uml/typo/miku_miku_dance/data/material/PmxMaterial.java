package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material;

import org.joml.Vector3f;
import org.joml.Vector4f;

public class PmxMaterial {

    public final String localTextureName, engTextureName, metaData;
    public final Vector4f diffuseColor, edgeColor;
    public final Vector3f specularColor, ambientColor;
    public final float shininess, edgePercentage;
    public final PmxMaterialFlags flags;
    public final Number textureIndex, sphereTextureIndex, toonTextureIndex;
    public final byte blendingMode;
    public final boolean usingInternalTexture;
    public final int meshCount;

    public PmxMaterial(String localTextureName, String engTextureName,
                       Vector4f diffuseColor, Vector3f specularColor,
                       float shininess, Vector3f ambientColor,
                       PmxMaterialFlags flags,
                       Vector4f edgeColor,
                       float edgePercentage,
                       Number textureIndex,
                       Number sphereTextureIndex,
                       byte blendingMode,
                       byte internalTextureFlag,
                       Number toonTextureIndex,
                       String metaData,
                       int meshCount) {
        this.localTextureName = localTextureName;
        this.engTextureName = engTextureName;
        this.diffuseColor = diffuseColor;
        this.specularColor = specularColor;
        this.shininess = shininess;
        this.ambientColor = ambientColor;
        this.flags = flags;
        this.edgeColor = edgeColor;
        this.edgePercentage = edgePercentage;
        this.textureIndex = textureIndex;
        this.sphereTextureIndex = sphereTextureIndex;
        this.blendingMode = blendingMode;
        this.usingInternalTexture = internalTextureFlag != 0;
        this.toonTextureIndex = toonTextureIndex;
        this.metaData = metaData;
        this.meshCount = meshCount;
    }
}
