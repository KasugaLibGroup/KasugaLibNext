package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.view.ResourceTypeDetector;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ResourceTypeDetectorTest {
    @Test void signatureWinsOverMisleadingExtension() {
        var png = ResourceTypeDetector.detect("pretend.js", new byte[]{(byte)137,80,78,71,13,10,26,10});
        assertEquals("texture", png.type());
        assertEquals("png", png.subtype());
        assertEquals(ResourceTypeDetector.Evidence.MAGIC, png.evidence());
        assertEquals("model", ResourceTypeDetector.detect("unknown.bin", "glTF\2\0\0\0".getBytes(StandardCharsets.US_ASCII)).type());
        assertEquals("pmx", ResourceTypeDetector.detect("unknown", "PMX ".getBytes(StandardCharsets.US_ASCII)).subtype());
    }

    @Test void textHintsAreExplicitlyWeakerThanSignaturesAndNeverExecutionProof() {
        assertEquals(ResourceTypeDetector.Evidence.EXTENSION,
                ResourceTypeDetector.detect("train.bbmodel", "{\"meta\":{}}".getBytes(StandardCharsets.UTF_8)).evidence());
        assertEquals("binary", ResourceTypeDetector.detect("payload.js", new byte[]{0,1,2,3}).type());
        assertNotEquals("audio", ResourceTypeDetector.detect("x.wav", "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII)).type());
        assertEquals(ResourceTypeDetector.Evidence.UNKNOWN, ResourceTypeDetector.detect("x", new byte[]{1,2}).evidence());
    }
}
