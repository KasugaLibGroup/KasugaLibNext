package lib.kasuga.rendering.models.uml.math;

import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;

public record BoneContext<T extends BoneData>(Bone bone, float weight, T data,
                                              Transform transform,
                                              Transform bindTransform,
                                              Transform absTransform,
                                              Transform invTransform) {
}
