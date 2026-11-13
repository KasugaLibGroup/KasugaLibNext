package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.identity.MinecraftNameCodec;
import lib.kasuga.core.resource.identity.ResourceUri;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceIdentityTest {
    @Test
    void concreteProviderIdentityPreservesRootsCaseUnicodeAndChildKeys() {
        var uri = ResourceUri.file("demo", "main", "models/列车 A.bbmodel").child("texture/UUID-A");
        assertEquals(uri, ResourceUri.parse(uri.toString()));
        assertTrue(uri.toString().contains("%E5%88%97"));
        assertNotEquals(uri, ResourceUri.file("demo", "other", "models/列车 A.bbmodel").child("texture/UUID-A"));
        assertNotEquals(uri, ResourceUri.file("demo", "main", "models/列车 a.bbmodel").child("texture/UUID-A"));
        assertEquals(uri, MinecraftNameCodec.decode(MinecraftNameCodec.encode(uri, ".png"), ".png"));
    }

    @Test
    void rejectsAmbiguousAndUnsafeIdentitiesBeforeNormalization() {
        for (String path : new String[]{"/x", "x/", "a//b", "a/./b", "a/../b", "a\\b", "C:/x"}) {
            assertThrows(IllegalArgumentException.class, () -> ResourceUri.file("demo", "main", path), path);
        }
        for (String value : new String[]{"ksg://demo/roots/main/%2E%2E/x", "ksg://demo/roots/main/a%2Fb",
                "ksg://demo/roots/main/a%5Cb", "ksg://demo/roots/main/%C0%AF", "ksg://demo/roots/main/%252e%252e/x",
                "ksg://demo/roots/main/a?query", "ksg://demo/roots/main/%FF"}) {
            assertThrows(IllegalArgumentException.class, () -> ResourceUri.parse(value), value);
        }
    }
}
