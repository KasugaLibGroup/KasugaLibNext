package lib.kasuga.rendering.models.uml.loaders;

import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

public class SpriteSetBuilder<TextureIdentifier> {

    private final MaterialSetBuilder<TextureIdentifier> builder;
    private TextureIdentifier textureId;

    private Vector2f uv0, uv1, uv2, uv3;

    private boolean shade;
    private boolean flipU, flipV;
    private boolean ambientOcclusion;
    private boolean culled;
    private boolean emissive;
    private Vector4f color, ambient, specular;

    private SpriteData currentSpriteData;

    private SpriteSetData currentSpriteSetData;

    private final List<Sprite> sprites;

    @Getter
    private final List<SpriteSet> spriteSets;

    public SpriteSetBuilder(MaterialSetBuilder<TextureIdentifier> builder) {
        sprites = new ArrayList<>();
        this.builder = builder;
        this.spriteSets = new ArrayList<>();
        clearSprite();
    }

    public void clearSprite() {
        textureId = null;
        flipU = false;
        flipV = false;
        ambientOcclusion = false;
        culled = false;
        emissive = false;
        shade = false;
        uv0 = new Vector2f();
        uv1 = new Vector2f(1, 0);
        uv2 = new Vector2f(1, 1);
        uv3 = new Vector2f(0, 1);
        color = new Vector4f(1, 1, 1, 1);
        ambient = new Vector4f(1, 1, 1, 1);
        specular = new Vector4f(1, 1, 1, 1);
        currentSpriteData = null;
    }

    public SpriteSetBuilder<TextureIdentifier> endSprite() {
        Sprite sprite = new Sprite(builder.getTexture(textureId), uv0, uv1, uv2, uv3, color, ambient, specular, currentSpriteData);
        sprite.shade = shade;
        sprite.flipU = flipU;
        sprite.flipV = flipV;
        sprite.ambientOcclusion = ambientOcclusion;
        sprite.culled = culled;
        sprite.emissive = emissive;
        sprites.add(sprite);
        clearSprite();
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> textureId(TextureIdentifier textureId) {
        this.textureId = textureId;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> spriteData(SpriteData spriteData) {
        this.currentSpriteData = spriteData;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> spriteSetData(SpriteSetData spriteSetData) {
        this.currentSpriteSetData = spriteSetData;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> uv0(Vector2f uv0) {
        this.uv0 = uv0;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> uv1(Vector2f uv1) {
        this.uv1 = uv1;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> uv2(Vector2f uv2) {
        this.uv2 = uv2;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> uv3(Vector2f uv3) {
        this.uv3 = uv3;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> rectangularUVs(Vector2f uv0, Vector2f uv1) {
        this.uv0 = uv0;
        this.uv1 = new Vector2f(uv1.x, uv0.y);
        this.uv2 = uv1;
        this.uv3 = new Vector2f(uv0.x, uv1.y);
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> triangleUVs(Vector2f uv0, Vector2f uv1, Vector2f uv2) {
        this.uv0 = uv0;
        this.uv1 = uv1;
        this.uv2 = uv2;
        this.uv3 = uv2;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> shade(boolean shade) {
        this.shade = shade;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> flipU(boolean flipU) {
        this.flipU = flipU;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> flipV(boolean flipV) {
        this.flipV = flipV;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> ambientOcclusion(boolean ambientOcclusion) {
        this.ambientOcclusion = ambientOcclusion;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> culled(boolean culled) {
        this.culled = culled;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> emissive(boolean emissive) {
        this.emissive = emissive;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> color(Vector4f color) {
        this.color = color;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> ambient(Vector4f ambient) {
        this.ambient = ambient;
        return this;
    }

    public SpriteSetBuilder<TextureIdentifier> specular(Vector4f specular) {
        this.specular = specular;
        return this;
    }

    public void clearCache() {
        sprites.clear();
        spriteSets.clear();
    }

    public SpriteSet endSpriteSet() {
        if (textureId != null) {endSprite();}
        SpriteSet spriteSet = new SpriteSet(currentSpriteSetData, sprites.toArray(new Sprite[0]));
        this.currentSpriteSetData = null;
        this.spriteSets.add(spriteSet);
        return spriteSet;
    }

    public SpriteSetBuilder<TextureIdentifier> clearCachedSets() {
        this.spriteSets.clear();
        return this;
    }
}
