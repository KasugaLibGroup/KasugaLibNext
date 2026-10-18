package lib.kasuga.rendering.models.mc.java_and_bedrock.data;

import lib.kasuga.rendering.models.uml.structure.material.Texture;
import net.minecraft.client.resources.model.Material;

import java.util.Objects;
import java.util.function.Supplier;

public class MCTexture extends Texture {

    private final Supplier<Material> materialSupplier;
    private Material material;

    public MCTexture(String id, Supplier<Material> materialSupplier,
                     float textureWidth,
                     float textureHeight,
                     MCTextureData data) {
        super(id, textureWidth, textureHeight, data);
        this.materialSupplier = materialSupplier;
        this.material = null;
    }

    public Material getMaterial() {
        if (material != null) return material;
        material = materialSupplier.get();
        Objects.requireNonNull(material);
        return material;
    }
}
