package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd;

import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.Map;

/** A static MMD pose, including MikuMikuMoving's optional morph blocks. */
public record VpdPose(String modelName, Map<String, Transform> bones, Map<String, Float> morphs) {
    public VpdPose {
        bones = Map.copyOf(bones);
        morphs = Map.copyOf(morphs);
    }
}
