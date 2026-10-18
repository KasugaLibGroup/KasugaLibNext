package lib.kasuga.rendering.models.mc.backend.data_type;

import lombok.Getter;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector4f;

@Getter
@Deprecated
public class KsgBakedQuad extends BakedQuad implements MeshNormalHolder, MeshColorHolder {

    private final Vector3f meshNormal;
    private final Vector4f meshColor;

    public KsgBakedQuad(int[] vertices, Vector3f meshNormal, Vector4f meshColor, TextureAtlasSprite sprite, boolean shade, boolean hasAO) {
        super(vertices, -1, Direction.DOWN, sprite, shade, hasAO);
        this.meshNormal = meshNormal;
        this.meshColor = meshColor;
    }
}
