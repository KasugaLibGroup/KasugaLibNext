package lib.kasuga.rendering.models.uml.structure.skeleton;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

@Getter
public class Anchor {

    @NonNull
    private final String name;

    @NonNull
    private final BoneBinding binding;

    @NonNull
    @Setter
    private Transform transform;

    @Nullable
    private final AnchorData data;

    public Anchor(@NonNull String name, @NonNull BoneBinding binding, @NonNull Transform transform, @Nullable AnchorData data) {
        this.name = name;
        this.binding = binding;
        this.transform = transform;
        this.data = data;
    }
}
