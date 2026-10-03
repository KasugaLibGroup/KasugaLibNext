package lib.kasuga.rendering.models.uml.dynamic.morph.results;

import lib.kasuga.rendering.models.uml.dynamic.morph.BlendMode;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lombok.Getter;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

@Getter
public class MaterialResult implements IMorphResult<Material> {

    private final Material original;

    private Vector4f color;
    private BlendMode colorBlendMode;
    private Vector4f colorMultiply;
    private Vector4f colorAdd;

    private Vector4f specular;
    private BlendMode specularBlendMode;

    private Vector4f ambient;
    private BlendMode ambientBlendMode;

    private Vector4f edgeColor;
    private BlendMode edgeColorBlendMode;

    private Integer spriteFrame;
    private Integer spriteSetIndex;
    private Integer materialFrame;
    private final Vector2f uv0Offset;
    private final Vector2f uv1Offset;
    private final Vector2f uv2Offset;
    private final Vector2f uv3Offset;

    public MaterialResult(Material original) {
        this.original = original;
        this.uv0Offset = new Vector2f();
        this.uv1Offset = new Vector2f();
        this.uv2Offset = new Vector2f();
        this.uv3Offset = new Vector2f();
    }

    /** Multiply color multiplier (MULTIPLY mode stacks multiplicatively). */
    public void addColor(Vector4f delta, BlendMode mode) {
        if (mode == BlendMode.MULTIPLY) {
            if (colorMultiply == null) colorMultiply = new Vector4f(1f);
            colorMultiply.mul(delta);
        } else {
            if (colorAdd == null) colorAdd = new Vector4f(0f, 0f, 0f, 0f);
            colorAdd.add(delta);
        }
        this.color = mode == BlendMode.MULTIPLY ? colorMultiply : colorAdd;
        this.colorBlendMode = mode;
    }
    /** Add specular delta (ADD mode stacks additively). */
    public void addSpecular(Vector4f delta, BlendMode mode) {
        if (this.specular == null) { this.specular = new Vector4f(delta); this.specularBlendMode = mode; }
        else this.specular.add(delta);
    }
    /** Add ambient delta. */
    public void addAmbient(Vector4f delta, BlendMode mode) {
        if (this.ambient == null) { this.ambient = new Vector4f(delta); this.ambientBlendMode = mode; }
        else this.ambient.add(delta);
    }
    /** Add edge color delta. */
    public void addEdgeColor(Vector4f delta, BlendMode mode) {
        if (this.edgeColor == null) { this.edgeColor = new Vector4f(delta); this.edgeColorBlendMode = mode; }
        else this.edgeColor.add(delta);
    }
    public void setSpriteFrame(int spriteSetIndex, int frameIndex) {
        this.spriteSetIndex = spriteSetIndex; this.spriteFrame = frameIndex;
    }

    public void setMaterialFrame(int frameIndex) {
        this.materialFrame = frameIndex;
    }

    public void offsetUv(Vector2f offset0, Vector2f offset1, Vector2f offset2, Vector2f offset3) {
        this.uv0Offset.add(offset0);
        this.uv1Offset.add(offset1);
        this.uv2Offset.add(offset2);
        this.uv3Offset.add(offset3);
    }

    @Override
    public void reset() {
        this.color = null; this.colorBlendMode = null;
        this.colorMultiply = null; this.colorAdd = null;
        this.specular = null; this.specularBlendMode = null;
        this.ambient = null; this.ambientBlendMode = null;
        this.edgeColor = null; this.edgeColorBlendMode = null;
        this.spriteFrame = null; this.spriteSetIndex = null;
        this.materialFrame = null;
        this.uv0Offset.set(0, 0);
        this.uv1Offset.set(0, 0);
        this.uv2Offset.set(0, 0);
        this.uv3Offset.set(0, 0);
    }

    @Override
    public boolean isEmpty() {
        return color == null && specular == null && ambient == null
                && edgeColor == null && spriteFrame == null && materialFrame == null &&
                uv0Offset.lengthSquared() == 0 && uv1Offset.lengthSquared() == 0 &&
                uv2Offset.lengthSquared() == 0 && uv3Offset.lengthSquared() == 0;
    }
}
