package lib.kasuga.rendering.models.mc.api.pbr;

/** LabPBR predefined metal values used by Kasuga's specular-map G channel. */
public enum PbrMetalPreset {
    IRON(230),
    GOLD(231),
    ALUMINUM(232),
    CHROME(233),
    COPPER(234),
    LEAD(235),
    PLATINUM(236),
    SILVER(237),
    ALBEDO_COLORED(255);

    private final int f0Code;

    PbrMetalPreset(int f0Code) {
        this.f0Code = f0Code;
    }

    public int f0Code() {
        return f0Code;
    }
}
