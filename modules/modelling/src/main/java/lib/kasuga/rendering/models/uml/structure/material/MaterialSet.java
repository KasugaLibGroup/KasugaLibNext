package lib.kasuga.rendering.models.uml.structure.material;

import lib.kasuga.rendering.models.uml.structure.material.animators.Animator;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MaterialSet {

    @Getter
    @NonNull
    private final Texture[] textures;

    @Getter
    @NonNull
    private final Material[] materials;

    @Getter
    private final SpriteSet[] spriteSets;

    @Getter
    private final Integer[] materialBySprites;

    @Getter
    private final Map<Material, Integer> indexByMaterial;

    @Getter
    private final Map<SpriteSet, Integer> indexBySpriteSet;

    public MaterialSet(@NotNull Texture texture, @NotNull Material material) {
        this(java.util.List.of(texture), java.util.List.of(material));
    }

    public MaterialSet(Collection<Texture> textures, @NonNull Collection<Material> materials) {
        this.textures = textures.toArray(new Texture[0]);
        this.materials = materials.toArray(new Material[0]);
        ArrayList<SpriteSet> spriteSets = new ArrayList<>();
        ArrayList<Integer> materialBySprites = new ArrayList<>();
        indexByMaterial = new HashMap<>();
        indexBySpriteSet = new HashMap<>();
        int i = 0, j = 0;
        for (Material material : materials) {
            indexByMaterial.put(material, i);
            for (SpriteSet spriteSet : material.getSprites()) {
                spriteSets.add(spriteSet);
                indexBySpriteSet.put(spriteSet, j++);
                materialBySprites.add(i);
            }
            i++;
        }
        this.spriteSets = spriteSets.toArray(new SpriteSet[0]);
        this.materialBySprites = materialBySprites.toArray(new Integer[0]);
    }

    public Material getMaterial(SpriteSet set) {
        return materials[materialBySprites[indexBySpriteSet.get(set)]];
    }
}
