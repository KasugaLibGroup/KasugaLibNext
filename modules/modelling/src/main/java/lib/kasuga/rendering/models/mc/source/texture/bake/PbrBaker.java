package lib.kasuga.rendering.models.mc.source.texture.bake;

import java.awt.image.BufferedImage;

public interface PbrBaker {
    PbrBakeResult bake(BufferedImage source, PbrBakeProfile profile);
}
