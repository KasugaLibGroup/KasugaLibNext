package lib.kasuga.rendering.models.mc.api.pbr;

import net.minecraft.resources.ResourceLocation;

/** Snapshot of PMX material data exposed to conversion rules. */
public record PbrMaterialContext(
        ResourceLocation modelId,
        ResourceLocation textureId,
        int materialIndex,
        String localName,
        String englishName,
        String metadata,
        float diffuseRed,
        float diffuseGreen,
        float diffuseBlue,
        float diffuseAlpha,
        float specularRed,
        float specularGreen,
        float specularBlue,
        float ambientRed,
        float ambientGreen,
        float ambientBlue,
        float shininess,
        boolean noCull,
        boolean receivesShadow,
        int textureWidth,
        int textureHeight
) {}
