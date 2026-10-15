package lib.kasuga.rendering.models.mc.source.texture.bake;

import lib.kasuga.rendering.models.mc.api.pbr.PbrConversionSettings;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record PbrBakeProfile(
        float smoothness,
        int f0Code,
        float sssStrength,
        float normalStrength,
        float emissionStrength
) {
    public static final int VERSION = 2;

    public static PbrBakeProfile from(PmxMaterial material) {
        float normalizedShininess = Math.clamp(material.shininess / 128.0f, 0.0f, 1.0f);
        float smoothness = 0.18f + (float) Math.sqrt(normalizedShininess) * 0.72f;

        // PMX has no reliable metalness, SSS or emission semantics. Material
        // names are deliberately ignored: automatic baking must not depend on
        // author language or naming conventions. The PMX specular colour is
        // only used as a conservative dielectric F0 estimate; values >= 230
        // are reserved for LabPBR's predefined metals and are never generated
        // automatically.
        float specular = (material.specularColor.x + material.specularColor.y + material.specularColor.z) / 3.0f;
        int f0Code = Math.clamp(Math.round(specular * 255.0f), 4, 90);
        return new PbrBakeProfile(smoothness, f0Code, 0.0f, 0.08f, 0.0f);
    }

    public static PbrBakeProfile from(PbrConversionSettings settings) {
        return new PbrBakeProfile(
                settings.smoothness(), settings.f0Code(), settings.subsurface(),
                settings.normalStrength(), settings.emission()
        );
    }

    public PbrConversionSettings toSettings() {
        return new PbrConversionSettings(
                smoothness, f0Code, sssStrength, normalStrength, emissionStrength
        );
    }

    public String cacheDescriptor() {
        return VERSION + ":" + smoothness + ":" + f0Code + ":" + sssStrength + ":" + normalStrength + ":" + emissionStrength;
    }

    /**
     * Returns the atlas location for this exact conversion profile. Materials
     * may share their decoded source image, but they must not share an atlas
     * sprite when their final PBR settings differ.
     */
    public ResourceLocation variantLocation(ResourceLocation source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cacheDescriptor().getBytes(StandardCharsets.UTF_8));
            String suffix = HexFormat.of().formatHex(hash, 0, 8);
            return ResourceLocation.fromNamespaceAndPath(
                    source.getNamespace(), source.getPath() + "__pbr_" + suffix
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
