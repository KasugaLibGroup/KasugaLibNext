package lib.kasuga.rendering.models.mc.api.pbr;

/**
 * Immutable output settings consumed by Kasuga's stylized PBR baker.
 *
 * @param smoothness       LabPBR perceptual smoothness, in [0, 1]
 * @param f0Code           LabPBR specular G byte: 0-229 dielectric, 230-254 predefined metal, 255 albedo metal
 * @param subsurface       subsurface-scattering strength, in [0, 1]
 * @param normalStrength   strength of normals derived from albedo luminance, in [0, 1]
 * @param emission         emission strength, in [0, 1]
 */
public record PbrConversionSettings(
        float smoothness,
        int f0Code,
        float subsurface,
        float normalStrength,
        float emission
) {
    public PbrConversionSettings {
        smoothness = Math.clamp(smoothness, 0.0f, 1.0f);
        f0Code = Math.clamp(f0Code, 0, 255);
        subsurface = Math.clamp(subsurface, 0.0f, 1.0f);
        normalStrength = Math.clamp(normalStrength, 0.0f, 1.0f);
        emission = Math.clamp(emission, 0.0f, 1.0f);
    }

    public PbrConversionSettings withSmoothness(float value) {
        return new PbrConversionSettings(value, f0Code, subsurface, normalStrength, emission);
    }

    public PbrConversionSettings withF0Code(int value) {
        return new PbrConversionSettings(smoothness, value, subsurface, normalStrength, emission);
    }

    public PbrConversionSettings withDielectricF0(float value) {
        return withF0Code(Math.clamp(Math.round(value * 255.0f), 0, 229));
    }

    public PbrConversionSettings withMetal(PbrMetalPreset preset) {
        return withF0Code(preset.f0Code());
    }

    public PbrConversionSettings withSubsurface(float value) {
        return new PbrConversionSettings(smoothness, f0Code, value, normalStrength, emission);
    }

    public PbrConversionSettings withNormalStrength(float value) {
        return new PbrConversionSettings(smoothness, f0Code, subsurface, value, emission);
    }

    public PbrConversionSettings withEmission(float value) {
        return new PbrConversionSettings(smoothness, f0Code, subsurface, normalStrength, value);
    }
}
