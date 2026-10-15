package lib.kasuga.rendering.models.uml.loaders;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lombok.Getter;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class MaterialSetBuilder<TextureIdentifier> {

    @Getter
    private final ModelLoader loader;

    @Getter
    private final Map<Object, Texture> textures;

    @Getter
    private final List<Material> materials;

    private final List<TextureIdentifier> currentTextureIds;
    private final SpriteSetBuilder<TextureIdentifier> spriteSetBuilder;
    private MaterialData currentMaterialData;
    private final Map<Material, List<TriConsumer<MaterialSetBuilder<TextureIdentifier>, SpriteSetBuilder<TextureIdentifier>, Material>>> spriteBuildingFunctions;
    private final Map<Material, List<TextureIdentifier>> materialTextureIds;
    private final List<TriConsumer<MaterialSetBuilder<TextureIdentifier>, SpriteSetBuilder<TextureIdentifier>, Material>> currentSpriteBuildingFunctions;
    private final Map<Predicate<Object>, TextureIdentifier> fallbacks;
    private final Map<TextureIdentifier, Supplier<Texture>> fallbackSuppliers;

    @Getter
    private final Map<Object, Material> materialByIds;

    public MaterialSetBuilder(ModelLoader loader) {
        this.loader = loader;
        this.textures = new HashMap<>();
        this.materials = new ArrayList<>();
        this.currentTextureIds = new ArrayList<>();
        this.currentMaterialData = null;
        this.spriteSetBuilder = new SpriteSetBuilder<>(this);
        this.spriteBuildingFunctions = new HashMap<>();
        this.currentSpriteBuildingFunctions = new ArrayList<>();
        this.materialTextureIds = new HashMap<>();
        this.fallbacks = new HashMap<>();
        this.fallbackSuppliers = new HashMap<>();
        this.materialByIds = new HashMap<>();
    }

    public MaterialSetBuilder<TextureIdentifier> registerFallback(Predicate<Object> predicate, TextureIdentifier identifier, Supplier<Texture> fallbackSupplier) {
        fallbacks.put(predicate, identifier);
        fallbackSuppliers.put(identifier, fallbackSupplier);
        return this;
    }

    public MaterialSetBuilder<TextureIdentifier> beginMaterial() {
        if (!currentTextureIds.isEmpty()) endMaterial();
        currentTextureIds.clear();
        currentMaterialData = null;
        return this;
    }

    public MaterialSetBuilder<TextureIdentifier> registerTexture(Object id, Texture texture) {
        textures.put(id, texture);
        return this;
    }

    public MaterialSetBuilder<TextureIdentifier> useTexture(Collection<TextureIdentifier> textureIds) {
        currentTextureIds.addAll(textureIds);
        return this;
    }

    public MaterialSetBuilder<TextureIdentifier> useTexture(TextureIdentifier textureId) {
        currentTextureIds.add(textureId);
        return this;
    }

    public MaterialSetBuilder<TextureIdentifier> setMaterialData(MaterialData materialData) {
        this.currentMaterialData = materialData;
        return this;
    }

    public MaterialSetBuilder<TextureIdentifier> addSpriteBuildingFunc(TriConsumer<MaterialSetBuilder<TextureIdentifier>, SpriteSetBuilder<TextureIdentifier>, Material> function) {
        currentSpriteBuildingFunctions.add(function);
        return this;
    }

    public Texture getTexture(TextureIdentifier id) {
        return textures.get(id);
    }

    public void nextFrame() {
        spriteSetBuilder.endSpriteSet();
    }

    public MaterialSetBuilder<TextureIdentifier> endMaterial() {
        return endMaterial(null);
    }

    public MaterialSetBuilder<TextureIdentifier> endMaterial(@Nullable Object id) {
        Material material = new Material(new Texture[currentTextureIds.size()], currentMaterialData);
        int i = 0;
        for (TextureIdentifier identifier : currentTextureIds) {
            if (!textures.containsKey(identifier)) continue;
            material.getTextures()[i] = textures.get(identifier);
            i++;
        }
        material.hookTextures();
        materialTextureIds.put(material, new ArrayList<>(currentTextureIds));
        currentTextureIds.clear();
        spriteBuildingFunctions.put(material, new ArrayList<>(currentSpriteBuildingFunctions));
        currentSpriteBuildingFunctions.clear();
        currentMaterialData = null;
        materials.add(material);
        if (id != null) {
            materialByIds.put(id, material);
        }
        return this;
    }

    public Material getNamedMaterial(Object id) {
        return materialByIds.get(id);
    }

    public MaterialSet endMaterialSet() {
        fallbackSuppliers.forEach((k, v) -> {
            if (textures.containsKey(k)) return;
            textures.put(k, v.get());
        });
        for (Material mat : materials) {
            List<TextureIdentifier> textureIds = materialTextureIds.get(mat);
            List<TriConsumer<MaterialSetBuilder<TextureIdentifier>, SpriteSetBuilder<TextureIdentifier>, Material>> consumers = spriteBuildingFunctions.get(mat);
            int i = 0;
            for (TextureIdentifier identifier : textureIds) {
                if (textures.containsKey(identifier)) {
                    i++;
                    continue;
                }
                Texture tex = loader.loadTexture(identifier);
                if (tex == null) {
                    for (Map.Entry<Predicate<Object>, TextureIdentifier> entry : fallbacks.entrySet()) {
                        if (entry.getKey().test(identifier)) {
                            tex = textures.get(entry.getValue());
                            break;
                        }
                    }
                    if (tex == null) continue;
                }
                textures.put(identifier, tex);
                mat.getTextures()[i] = tex;
                i++;
            }
            for (TriConsumer<MaterialSetBuilder<TextureIdentifier>, SpriteSetBuilder<TextureIdentifier>, Material> consumer : consumers) {
                consumer.accept(this, spriteSetBuilder, mat);
            }
            if (spriteSetBuilder.getSpriteSets().isEmpty()) {
                spriteSetBuilder.endSpriteSet();
            }
            for (SpriteSet set : spriteSetBuilder.getSpriteSets()) {
                mat.addSprite(set);
            }
            spriteSetBuilder.clearCache();
            mat.hookTextures();
        }
        MaterialSet materialSet = new MaterialSet(textures.values(), materials);
        clear();
        return materialSet;
    }

    public void clear() {
        textures.clear();
        materials.clear();
        currentTextureIds.clear();
        currentMaterialData = null;
        spriteSetBuilder.getSpriteSets().clear();
        spriteBuildingFunctions.clear();
        materialTextureIds.clear();
        currentSpriteBuildingFunctions.clear();
        materialByIds.clear();
    }
}
