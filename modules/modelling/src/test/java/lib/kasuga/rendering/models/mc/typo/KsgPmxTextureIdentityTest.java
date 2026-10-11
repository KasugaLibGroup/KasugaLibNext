package lib.kasuga.rendering.models.mc.typo;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KsgPmxTextureIdentityTest {
    @Test void sameEntryNamesInDifferentArchivesMustNotShareAtlasSprites() {
        var blue = KsgPmxLoader.textureLocation(ResourceLocation.parse("test:short.mmd.zip"), "model.pmx", "textures/dress.jpg");
        var rose = KsgPmxLoader.textureLocation(ResourceLocation.parse("test:short_rose.mmd.zip"), "model.pmx", "textures/dress.jpg");
        assertNotEquals(blue, rose);
        assertEquals(blue, KsgPmxLoader.textureLocation(ResourceLocation.parse("test:short.mmd.zip"), "model.pmx", "textures/dress.jpg"));
    }
    @Test void modelAndTextureEntriesBothContributeToStableUnicodeSafeIdentity() {
        var original = KsgPmxLoader.textureLocation("衣服.zip", "模型.pmx", "纹理/身体.png");
        assertNotEquals(original, KsgPmxLoader.textureLocation("衣服.zip", "其他.pmx", "纹理/身体.png"));
        assertNotEquals(original, KsgPmxLoader.textureLocation("衣服.zip", "模型.pmx", "纹理/眼睛.png"));
        assertTrue(original.getPath().matches("textures/pmx/[0-9a-f]{64}"));
        // These two names deliberately collide under String.hashCode().
        assertNotEquals(KsgPmxLoader.textureLocation("same.zip", "model.pmx", "Aa"),
                KsgPmxLoader.textureLocation("same.zip", "model.pmx", "BB"));
    }
}
