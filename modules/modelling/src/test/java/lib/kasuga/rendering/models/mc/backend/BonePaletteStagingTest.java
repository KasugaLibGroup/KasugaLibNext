package lib.kasuga.rendering.models.mc.backend;
import lib.kasuga.rendering.models.mc.backend.transform.BoneTransformTBO;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BonePaletteStagingTest {
    @Test void batchPaletteCanBeStagedAndUpdatedWithoutAnyOpenGlContext() throws Exception {
        var instance=ModelInstanceFixture.minimal();instance.update();
        try(var tbo=new BoneTransformTBO(instance.getSkeletonInstance())) {
            assertEquals(0,tbo.getBufferId());assertTrue(tbo.isValid());
            assertEquals(36,tbo.getUploadCache().remaining());
            long before=tbo.getSkeletonVersion();instance.forceUpdate();instance.update();tbo.updateForVersion();
            assertTrue(tbo.getSkeletonVersion()>before);assertEquals(0,tbo.getBufferId());
        }
    }
}
