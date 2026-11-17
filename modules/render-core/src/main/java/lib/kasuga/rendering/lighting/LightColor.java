package lib.kasuga.rendering.lighting;

/** Linear RGB light tint. Intensity is controlled separately by {@link LightSource}. */
public record LightColor(float red, float green, float blue) {
    public static final LightColor WHITE = new LightColor(1, 1, 1);

    public LightColor {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)
                || red < 0 || green < 0 || blue < 0) {
            throw new IllegalArgumentException("Light color must be finite and nonnegative");
        }
    }

    /**
     * Approximate black-body tint, from 1000 to 40000 kelvin, converted from sRGB to linear RGB.
     * Uses Tanner Helland's temperature fit:
     * https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html
     */
    public static LightColor temperature(float kelvin) {
        if (!Float.isFinite(kelvin) || kelvin < 1000 || kelvin > 40000) {
            throw new IllegalArgumentException("Color temperature must be between 1000 and 40000 K");
        }
        double temperature = kelvin / 100.0;
        double red = temperature <= 66 ? 255 : 329.698727446 * Math.pow(temperature - 60, -.1332047592);
        double green = temperature <= 66
                ? 99.4708025861 * Math.log(temperature) - 161.1195681661
                : 288.1221695283 * Math.pow(temperature - 60, -.0755148492);
        double blue = 255;
        if (temperature <= 19) blue = 0;
        else if (temperature < 66) blue = 138.5177312231 * Math.log(temperature - 10) - 305.0447927307;
        return new LightColor(linear(red), linear(green), linear(blue));
    }

    private static float linear(double channel) {
        double value = Math.clamp(channel / 255, 0, 1);
        return (float) (value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4));
    }

    /** Converts conventional display RGB components in [0,1] to a linear light tint. */
    public static LightColor srgb(float red, float green, float blue) {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)
                || red < 0 || green < 0 || blue < 0 || red > 1 || green > 1 || blue > 1) {
            throw new IllegalArgumentException("sRGB components must be in [0,1]");
        }
        return new LightColor(linear(red * 255), linear(green * 255), linear(blue * 255));
    }
}
