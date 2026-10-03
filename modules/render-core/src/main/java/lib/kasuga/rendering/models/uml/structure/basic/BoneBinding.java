package lib.kasuga.rendering.models.uml.structure.basic;

import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

@Getter
public class BoneBinding {

    private final Pair<Bone, Float>[] weights;

    @Nullable
    private final BoneBindingData data;

    @Getter
    @NonNull
    private final BoneBindingFunc func;

    public BoneBinding(Pair<Bone, Float>[] weights, BoneBindingFunc func, BoneBindingData data) {
        this.weights = weights.clone();
        this.data = data;
        this.func = func;
        if (this.weights.length < 1) return;
        double sum = 0.0;
        for (Pair<Bone, Float> weight : this.weights) {
            float value = weight.getSecond();
            if (!Float.isFinite(value) || value < 0f) {
                throw new IllegalArgumentException("Bone weights must be finite and non-negative");
            }
            sum += value;
        }
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Bone weights must have a finite positive sum");
        }
        if (Math.abs(sum - 1.0) <= 1.0e-7) return;
        for (int i = 0; i < this.weights.length; i++) {
            Pair<Bone, Float> weight = this.weights[i];
            this.weights[i] = Pair.of(weight.getFirst(), (float) (weight.getSecond() / sum));
        }
    }
}
