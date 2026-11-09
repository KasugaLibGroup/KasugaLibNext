package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeferredModelPoseTargetTest {

    private static final float EPS = 1.0e-5f;

    @Test
    void publicationBeforeBindIsReplayedOnFirstBind() {
        DeferredModelPoseTarget target = new DeferredModelPoseTarget();
        ModelInstance model = minimalModel();
        target.publish(publication(1L, 8f));

        target.rebind(model);
        target.sample(context("frame-1"));

        assertEquals(8f, rootX(model), EPS);
    }

    @Test
    void rebindMovesTheExistingDelegateWithoutDroppingTheLatestPublication() {
        DeferredModelPoseTarget target = new DeferredModelPoseTarget();
        ModelInstance first = minimalModel();
        ModelInstance second = minimalModel();
        target.rebind(first);
        target.publish(publication(1L, 8f));
        target.sample(context("frame-2"));

        target.rebind(second);
        target.sample(context("frame-3"));

        assertEquals(8f, rootX(first), EPS);
        assertEquals(8f, rootX(second), EPS);
    }

    @Test
    void samplingBeforeBindIsANoOp() {
        DeferredModelPoseTarget target = new DeferredModelPoseTarget();

        assertDoesNotThrow(() -> {
            target.sample(context("frame-4"));
            target.sample(publication(1L, 8f), context("frame-5"));
        });
    }

    @Test
    void closeIsIdempotentAndRejectsLaterUse() {
        DeferredModelPoseTarget target = new DeferredModelPoseTarget();
        ModelInstance model = minimalModel();
        target.rebind(model);
        target.publish(publication(1L, 8f));
        target.sample(context("frame-6"));
        assertEquals(8f, rootX(model), EPS);

        target.close();
        target.close();

        assertNull(rootTransform(model));
        assertThrows(IllegalStateException.class, () -> target.rebind(minimalModel()));
        assertThrows(IllegalStateException.class, () -> target.publish(publication(2L, 9f)));
    }

    private static TargetPublication<PoseOutput> publication(long revision, float x) {
        return new TargetPublication<>(
                AnimationTargetRef.modelPose(),
                revision,
                new OutputState.Active<>(
                        null,
                        new PoseOutput(Pose.bone("root", new Transform().translate(x, 0f, 0f))),
                        Continuity.SNAP));
    }

    private static RenderSampleContext context(String frame) {
        return new RenderSampleContext(
                new RenderFrameToken(frame), 1.0, FrameInputSnapshot.empty());
    }

    private static float rootX(ModelInstance model) {
        Transform transform = rootTransform(model);
        if (transform == null) {
            throw new AssertionError("root transform was not written");
        }
        return transform.getPosition().x;
    }

    private static Transform rootTransform(ModelInstance model) {
        Bone root = model.getSkeletonInstance().getSkeleton().getBoneMap().get("root");
        return model.getSkeletonInstance().getTransforms().get(root);
    }

    private static ModelInstance minimalModel() {
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
