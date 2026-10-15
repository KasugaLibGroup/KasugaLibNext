package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;

/**
 * Minimal {@link ModelInstance} for tests that need a real instance (skeleton/morph/material wiring) but no
 * geometry. Used to exercise {@link ModelInstance#animate(float)} / {@link PoseDriver} without standing up a
 * real model asset.
 */
public final class ModelInstanceFixture {

    private ModelInstanceFixture() {
    }

    public static ModelInstance minimal() {
        Bone root = new Bone("root", new Transform(), null);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Texture texture = new Texture("tex", 1f, 1f, null);
        Material material = new Material(new Texture[]{texture}, null);
        MaterialSet materialSet = new MaterialSet(texture, material);
        Model model = new Model(
                new Vertex[0], new Mesh[0], new Bone[]{root}, skeleton, materialSet,
                MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, new MaterialSetInstance(materialSet), null);
    }
}
