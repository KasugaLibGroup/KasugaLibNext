package lib.kasuga.rendering.models.mc.api.pbr;

@FunctionalInterface
public interface PbrConversionRule {
    /** Return the settings to pass to the next rule and ultimately to the baker. */
    PbrConversionSettings apply(PbrMaterialContext context, PbrConversionSettings current);
}
