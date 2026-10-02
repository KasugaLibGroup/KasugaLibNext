package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.morph.Morph;
import lib.kasuga.rendering.models.uml.dynamic.morph.MorphInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** ModelInstancePoseSink.applyPose tests: Pose → skeleton / morph / material-frame writes + differential channel reset. */
class ModelInstancePoseSinkApplyPoseTest {

    private static final float EPS = 1e-3f;

    private static Bone root(ModelInstance instance) {
        return instance.getSkeletonInstance().getSkeleton().getBoneMap().get("root");
    }

    private static Transform boneTransform(ModelInstance instance) {
        return instance.getSkeletonInstance().getTransforms().get(root(instance));
    }

    @Test
    void replaceModeWritesBoneTransform() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        ModelInstancePoseSink sink = new ModelInstancePoseSink(instance);
        Transform transform = new Transform().translate(1f, 2f, 3f);
        sink.applyPose(Pose.bone("root", transform, ApplyMode.REPLACE));
        Transform written = boneTransform(instance);
        assertEquals(1f, written.getPosition().x, EPS);
        assertEquals(2f, written.getPosition().y, EPS);
        assertEquals(3f, written.getPosition().z, EPS);
    }

    @Test
    void multiplyAndAddModes() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        ModelInstancePoseSink sink = new ModelInstancePoseSink(instance);
        sink.applyPose(Pose.bone("root", new Transform().translate(1f, 0f, 0f), ApplyMode.REPLACE));
        sink.applyPose(Pose.bone("root", new Transform().translate(0f, 2f, 0f), ApplyMode.ADD));
        sink.applyPose(Pose.bone("root", new Transform().translate(0f, 0f, 4f), ApplyMode.MULTIPLY));
        Transform written = boneTransform(instance);
        assertEquals(1f, written.getPosition().x, EPS);
        assertEquals(2f, written.getPosition().y, EPS);
        assertEquals(4f, written.getPosition().z, EPS);
    }

    @Test
    void differentialResetNeutralizesBoneAbsentThisFrame() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        ModelInstancePoseSink sink = new ModelInstancePoseSink(instance);
        sink.applyPose(Pose.bone("root", new Transform().translate(1f, 2f, 3f), ApplyMode.REPLACE));
        assertEquals(1f, boneTransform(instance).getPosition().x, EPS);
        sink.applyPose(Pose.empty()); // channel posed last frame but absent now → reset (override dropped → bind pose)
        assertNull(boneTransform(instance));
    }

    @Test
    void frameWriteSetsCurrentMaterialFrame() {
        Texture texture = new Texture("tex", 1f, 1f, null);
        Sprite sprite = new Sprite(texture, new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f(),
                null, null, null, null);
        Material material = new Material(new Texture[]{texture}, null);
        material.addSprite(new SpriteSet(null, sprite));
        material.addSprite(new SpriteSet(null, sprite));
        MaterialSet materialSet = new MaterialSet(List.of(texture), List.of(material));
        ModelInstance instance = fixture(materialSet, null, null);
        ModelInstancePoseSink sink = new ModelInstancePoseSink(instance);
        sink.applyPose(Pose.frame(0, 1));
        assertEquals(1, instance.getMaterialInstance().getCurrentMatFrame(material));
    }

    @Test
    void differentialResetDeactivatesMorphAbsentThisFrame() {
        // The minimal fixture's morph instance has no registered morphs, so build an instance whose
        // MorphInstance is a spy recording channel writes (activation itself remains a no-op).
        Morph<Object> morphDef = ModelInstanceFixture.minimal().getModel().getMorph();
        RecordingMorph morph = new RecordingMorph(morphDef);
        ModelInstance instance = fixture(null, morphDef, morph);
        ModelInstancePoseSink sink = new ModelInstancePoseSink(instance);
        sink.applyPose(Pose.morph("blink", 1f, 1f));
        assertEquals(List.of("blink"), morph.activations);
        sink.applyPose(Pose.empty());
        assertEquals(List.of("blink"), morph.deactivations);
    }

    /** Same minimal model as {@link ModelInstanceFixture#minimal()} but with an optional material set / morph instance. */
    private static ModelInstance fixture(MaterialSet materialSet, Morph<Object> morphDef, MorphInstance<Object> morph) {
        Bone root = new Bone("root", new Transform(), null);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Texture texture = new Texture("tex", 1f, 1f, null);
        Material material = materialSet != null ? materialSet.getMaterials()[0] : new Material(new Texture[]{texture}, null);
        MaterialSet set = materialSet != null ? materialSet : new MaterialSet(texture, material);
        lib.kasuga.rendering.models.uml.structure.Model model = new lib.kasuga.rendering.models.uml.structure.Model(
                new Vertex[0], new Mesh[0], new Bone[]{root}, skeleton, set,
                MeshMode.TRIANGLES, null, morphDef);
        return new ModelInstance(model, null, null, null, new MaterialSetInstance(set), morph);
    }

    /** MorphInstance spy recording morph channel writes. */
    static final class RecordingMorph extends MorphInstance<Object> {
        final List<Object> activations = new ArrayList<>();
        final List<Object> deactivations = new ArrayList<>();

        RecordingMorph(Morph<Object> morph) {
            super(morph);
        }

        @Override
        public boolean activateMorph(Object id, float value, float factor) {
            activations.add(id);
            return super.activateMorph(id, value, factor);
        }

        @Override
        public void deactivateMorph(Object id) {
            deactivations.add(id);
            super.deactivateMorph(id);
        }
    }
}
