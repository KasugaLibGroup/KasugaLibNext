package lib.kasuga.rendering.models.mc.source.texture;

import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Locale;
import java.util.Optional;

public class BufferedImageTextureSource extends TextureSource<Pair> {

    public BufferedImageTextureSource(String name) {
        super(name);
    }

    @Override
    public ResourceLocation toRL(Pair sourceIdentifier) {
        if (sourceIdentifier.getFirst() instanceof ResourceLocation rl) {
            return rl;
        } else if (sourceIdentifier.getFirst() instanceof String str) {
            ResourceLocation rl = ResourceLocation.tryBuild("kasuga_lib", str.toLowerCase(Locale.ROOT));
            if (rl != null) return rl;
        }
        return ResourceLocation.tryBuild("kasuga_lib", "textures/" + sourceIdentifier.getFirst().hashCode());
    }

    @Override
    public Optional<InputStream> getInput(Pair input) {
        try(ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write((BufferedImage) input.getSecond(), "png", baos);
            byte[] bytes = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            return Optional.of(bais);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<Pair> getInputType() {
        return Pair.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        if (!(input instanceof Pair<?,?> pair)) return false;
        return pair.getSecond() instanceof BufferedImage;
    }
}
