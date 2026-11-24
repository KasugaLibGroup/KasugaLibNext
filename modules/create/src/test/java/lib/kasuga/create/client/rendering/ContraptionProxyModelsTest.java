package lib.kasuga.create.client.rendering;

import lib.kasuga.scene.ScenePose;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContraptionProxyModelsTest {
    @Test
    void identicalLocalBlockPositionsOnTwoTrainsHaveDifferentModelIdentities() {
        BlockPos pos = new BlockPos(-2, 3, 4);
        var first = ContraptionProxyModels.instanceIdentity(new UUID(0, 1), pos);
        var second = ContraptionProxyModels.instanceIdentity(new UUID(0, 2), pos);

        assertNotEquals(first, second);
        assertEquals(first, ContraptionProxyModels.instanceIdentity(new UUID(0, 1), pos));
        assertNotEquals(first, ContraptionProxyModels.instanceIdentity(new UUID(0, 1), pos.above()));
    }

    @Test
    void anchorFollowsTranslatedRotatedAndSlopedHostWithoutBakingInACamera() {
        ScenePose host = new ScenePose(new Vec3(100, 20, -30),
                new Matrix4f().rotateY((float) Math.PI / 2).rotateX((float) Math.PI / 6));
        BlockPos local = new BlockPos(2, 3, 4);
        var anchor = ContraptionProxyModels.anchorTransform(host, local);
        Vec3 expected = host.toWorld(Vec3.atLowerCornerOf(local).add(0.5, 0, 0.5));
        Vector3f actual = anchor.apply(new Vector3f(0.5f, 0, 0.5f));

        assertEquals(expected.x, actual.x, 1e-5);
        assertEquals(expected.y, actual.y, 1e-5);
        assertEquals(expected.z, actual.z, 1e-5);
        assertTrue(anchor.normal().isFinite());
    }
}
