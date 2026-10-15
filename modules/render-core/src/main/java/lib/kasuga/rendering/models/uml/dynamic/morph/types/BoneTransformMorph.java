package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

@Getter
public class BoneTransformMorph<IdType> implements MorphType<Bone, Transform, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Bone original;
    private final IdType identifier;
    private final Transform targetTransform;

    public BoneTransformMorph(Bone original, IdType identifier, Transform targetTransform) {
        this.original = original;
        this.identifier = identifier;
        this.targetTransform = targetTransform;
    }

    @Override
    public boolean isValidMorphInput(Bone input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Transform morph(Bone input, float percentage, float factor) {
        float weight = percentage * factor;
        Transform current = new Transform();
        Matrix4f curM4 = current.transform();
        Matrix3f curM3 = current.normal();
        Matrix4f tarM4 = targetTransform.transform();
        Matrix3f tarM3 = targetTransform.normal();

        float[] c = new float[16], t = new float[16];
        curM4.get(c);
        tarM4.get(t);
        for (int i = 0; i < 16; i++) c[i] += (t[i] - c[i]) * weight;
        Matrix4f lerpM4 = new Matrix4f().set(c);

        curM3.get(c);
        tarM3.get(t);
        for (int i = 0; i < 9; i++) c[i] += (t[i] - c[i]) * weight;
        Matrix3f lerpM3 = new Matrix3f().set(c);

        return new Transform(lerpM4, lerpM3);
    }
}
