package lib.kasuga.rendering.models.uml.typo.gltf;

import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsReader;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** glTF has no standard rigid-body/IK tables. Node candidates require an explicit physics profile. */
public final class GltfSkeletonDynamicsReader implements SkeletonDynamicsReader<GltfModelData> {
    @Override
    public void read(GltfModelData data, SkeletonDynamicsBuilder builder) {
        List<RigidBody> candidates = new ArrayList<>();
        for (int index = 0; index < data.asset().nodes().size(); index++) {
            Bone bone = data.boneByNode().get(index);
            String name = data.asset().nodes().names()[index];
            candidates.add(new RigidBody(name, name, bone, 0, 0, RigidBody.CAPSULE,
                    new Vector3f(0.05f), new Vector3f(), new Vector3f(), 1f, 0f, 0f, 0f, 0.65f,
                    RigidBody.DYNAMIC));
        }
        Set<Bone> followers = new HashSet<>();
        data.asset().skins().forEach(skin -> {
            for (int index : skin.jointNodeIndices()) {
                Bone bone = data.boneByNode().get(index);
                if (bone != null) followers.add(bone);
            }
        });
        Vector3f scale = data.modelScale();
        builder.physics(new Physics(candidates, List.of(), scale, true, true,
                (float) Math.cbrt(scale.x * scale.y * scale.z), followers));
    }
}
