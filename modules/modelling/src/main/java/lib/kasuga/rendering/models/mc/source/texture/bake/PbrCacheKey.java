package lib.kasuga.rendering.models.mc.source.texture.bake;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Cache-compatible ARGB digest with 32 KiB of scratch space, independent of texture size. */
final class PbrCacheKey {
    private PbrCacheKey() {}
    static String compute(BufferedImage image, String descriptor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
            int[] pixels = new int[4096];
            ByteBuffer bytes = ByteBuffer.allocate(pixels.length * Integer.BYTES);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x += pixels.length) {
                int length = Math.min(pixels.length, image.getWidth() - x);
                image.getRGB(x, y, length, 1, pixels, 0, length);
                bytes.clear();
                for (int i = 0; i < length; i++) bytes.putInt(pixels[i]);
                digest.update(bytes.array(), 0, bytes.position());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
