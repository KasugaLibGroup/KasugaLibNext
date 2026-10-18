package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;

class VpdReaderTest {
    @Test
    void readsCp932BonesAndMikuMikuMovingMorphs() {
        String source = """
                Vocaloid Pose Data file

                model.pmx; // parent
                1; // bones

                Bone0{センター
                  1,2,3;
                  0,0,0,0;
                }
                Morph0{笑い
                  0.75;
                }
                """;
        VpdPose pose = new VpdReader().read(ByteBuffer.wrap(
                source.getBytes(Charset.forName("windows-31j"))));

        assertEquals("model.pmx", pose.modelName());
        assertEquals(new Vector3f(1, 2, 3), pose.bones().get("センター").getPosition());
        assertEquals(1f, pose.bones().get("センター").getRotation().w, 1e-6f);
        assertEquals(0.75f, pose.morphs().get("笑い"), 1e-6f);
    }

    @Test
    void validatesDeclaredBoneCountAndBlockShape() {
        String source = "Vocaloid Pose Data file\n\nmodel;\n1;\n";
        assertThrows(VpdFormatException.class, () -> new VpdReader().read(
                ByteBuffer.wrap(source.getBytes(Charset.forName("windows-31j")))));
    }
}
