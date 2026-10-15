package lib.kasuga.rendering.models.mc.source.texture.bake;

import java.awt.image.BufferedImage;

/**
 * Deterministic reference baker used during resource preparation. Its channel
 * packing matches ksglib_main.fsh. A render-thread shader backend can replace
 * this implementation without changing the cache or scheduling API.
 */
public final class StylizedPbrBaker implements PbrBaker {
    @Override
    public PbrBakeResult bake(BufferedImage source, PbrBakeProfile profile) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage normal = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        BufferedImage specular = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int smoothness = toByte(profile.smoothness());
        int sss = profile.sssStrength() <= 0.0f
                ? 0
                : Math.clamp(Math.round(65 + profile.sssStrength() * 190), 65, 255);
        int emission = toByte(profile.emissionStrength());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float left = luminance(source.getRGB(wrap(x - 1, width), y));
                float right = luminance(source.getRGB(wrap(x + 1, width), y));
                float up = luminance(source.getRGB(x, wrap(y - 1, height)));
                float down = luminance(source.getRGB(x, wrap(y + 1, height)));
                float nx = Math.clamp((left - right) * profile.normalStrength(), -1.0f, 1.0f);
                float ny = Math.clamp((up - down) * profile.normalStrength(), -1.0f, 1.0f);
                int encodedX = toByte(nx * 0.5f + 0.5f);
                int encodedY = toByte(ny * 0.5f + 0.5f);

                // normal RG, AO B, height A. Height 255 disables parallax until
                // a profile explicitly supplies reliable height information.
                normal.setRGB(x, y, argb(255, encodedX, encodedY, 255));
                // smoothness R, F0/metal code G, porosity/SSS B, emission A.
                specular.setRGB(x, y, argb(emission, smoothness, profile.f0Code(), sss));
            }
        }
        return new PbrBakeResult(normal, specular);
    }

    private static int wrap(int value, int size) {
        int result = value % size;
        return result < 0 ? result + size : result;
    }

    private static float luminance(int argb) {
        float r = ((argb >>> 16) & 0xff) / 255.0f;
        float g = ((argb >>> 8) & 0xff) / 255.0f;
        float b = (argb & 0xff) / 255.0f;
        return r * 0.2126f + g * 0.7152f + b * 0.0722f;
    }

    private static int toByte(float value) {
        return Math.clamp(Math.round(value * 255.0f), 0, 255);
    }

    private static int argb(int a, int r, int g, int b) {
        return (a & 0xff) << 24 | (r & 0xff) << 16 | (g & 0xff) << 8 | (b & 0xff);
    }
}
