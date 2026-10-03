package lib.kasuga.rendering.models.uml.structure.material;

import com.google.common.collect.ImmutableMap;
import lib.kasuga.rendering.models.uml.structure.material.animators.Animatable;
import lib.kasuga.rendering.models.uml.structure.material.animators.Animator;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.*;

public class Material {

    @Getter
    @NonNull
    private final Texture[] textures;

    @Getter
    private final Map<String, Texture> textureMap;

    @Getter
    private final MaterialData data;

    private final List<SpriteSet> sprites;

    public Material(@NotNull Texture[] textures, @Nullable MaterialData data) {
        this.textures = textures;
        this.textureMap = new HashMap<>();
        this.data = data;
        this.sprites = new ArrayList<>();
    }

    public void hookTextures() {
        for (Texture texture : textures) {
            if (texture == null) continue;
            textureMap.put(texture.getId(), texture);
        }
    }

    public void addSprite(@NonNull SpriteSet sprite) {
        sprites.add(sprite);
    }

    public int getTextureCount() {
        return textures.length;
    }

    public boolean hasTexture(String id) {
        return textureMap.containsKey(id);
    }

    public Texture getTexture(String id) {
        return textureMap.get(id);
    }

    public Texture getTextureOrDefault(String id, Texture defaultTexture) {
        return textureMap.getOrDefault(id, defaultTexture);
    }

    public List<SpriteSet> getSprites() {
        return sprites;
    }
}
