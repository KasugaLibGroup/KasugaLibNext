package lib.kasuga.rendering.models.uml.dynamic.morph.results;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;

/**
 * Morph result for a {@link Bone}. Stores only the properties that were modified
 * by active morphs; unmorphed properties remain {@code null}.
 */
@Getter
public class BoneResult implements IMorphResult<Bone> {

    private final Bone original;

    /** Morphed transform (BoneTransformMorph output), null if unchanged. */
    private Transform transform;

    public BoneResult(Bone original) {
        this.original = original;
    }

    /** Bone transforms compose by multiplication; latest wins for linear superposition. */
    public void setTransform(Transform transform) {
        this.transform = (this.transform == null) ? transform : this.transform.mul(transform);
    }

    @Override
    public void reset() { this.transform = null; }

    @Override
    public boolean isEmpty() { return transform == null; }
}
