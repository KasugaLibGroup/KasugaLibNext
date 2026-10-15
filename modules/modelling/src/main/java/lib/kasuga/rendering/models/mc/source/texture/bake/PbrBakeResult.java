package lib.kasuga.rendering.models.mc.source.texture.bake;

import java.awt.image.BufferedImage;

public record PbrBakeResult(BufferedImage normalMap, BufferedImage specularMap) {}
