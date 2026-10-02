package lib.kasuga.rendering.models.mc.source.texture.bake;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PbrCacheKeyTest {
    @Test void existingDiskCacheKeysSurviveStreamingAndRasterLayouts() throws Exception {
        for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY})
            for (int width : new int[]{1, 4095, 4096, 4097, 8201}) {
                var parent = new BufferedImage(width + 2, 7, type);
                var image = parent.getSubimage(1, 2, width, 3);
                var random = new Random(type * 31L + width);
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt());
                String descriptor = "2:0.18:90:0.2:0.08:0.0/材质";
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
                var bytes = ByteBuffer.allocate(8 + width * image.getHeight() * 4);
                bytes.putInt(width).putInt(image.getHeight());
                for (int argb : image.getRGB(0, 0, width, image.getHeight(), null, 0, width)) bytes.putInt(argb);
                digest.update(bytes.array());
                assertEquals(HexFormat.of().formatHex(digest.digest()), PbrCacheKey.compute(image, descriptor));
            }
    }
    @Test void alphaDimensionsAndProfileRemainPartOfIdentity() {
        var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x12345678); image.setRGB(1, 0, 0xffabcdef);
        String original = PbrCacheKey.compute(image, "profile-a");
        assertNotEquals(original, PbrCacheKey.compute(image, "profile-b"));
        image.setRGB(0, 0, 0x99345678);
        assertNotEquals(original, PbrCacheKey.compute(image, "profile-a"));
        var column = new BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB);
        column.setRGB(0, 0, 0x12345678); column.setRGB(0, 1, 0xffabcdef);
        assertNotEquals(original, PbrCacheKey.compute(column, "profile-a"));
    }
}
