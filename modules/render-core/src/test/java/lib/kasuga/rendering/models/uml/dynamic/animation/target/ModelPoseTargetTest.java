package lib.kasuga.rendering.models.uml.dynamic.animation.target;

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

class ModelPoseTargetTest {

    private static final float EPS = 1.0e-5f;

    @Test
    void sampleInterpolatesTheLatestPublicationIntoTheBoundModel() {
        ModelInstance model = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(model);
        target.publish(publication(1, poseX(0f), poseX(8f), Continuity.INTERPOLATE));

        target.sample(context("frame-1", 0.25));

        assertEquals(2f, rootX(model), EPS);
    }

    @Test
    void repeatingAFrameTokenDoesNotApplyANewerPublicationAgain() {
        ModelInstance model = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(model);
        RenderSampleContext context = context("frame-2", 0.0);
        target.publish(publication(1, poseX(0f), poseX(8f), Continuity.INTERPOLATE));
        target.sample(context);

        target.publish(publication(2, poseX(50f), poseX(80f), Continuity.INTERPOLATE));
        target.sample(context);

        assertEquals(0f, rootX(model), EPS);
    }

    @Test
    void rebindMovesWritesToAnotherModelWithoutResettingPublication() {
        ModelInstance first = minimalModel();
        ModelInstance second = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(first);
        target.publish(publication(1, poseX(0f), poseX(8f), Continuity.INTERPOLATE));
        target.sample(context("frame-3", 1.0));

        target.rebind(second);
        target.sample(context("frame-4", 0.5));

        assertEquals(8f, rootX(first), EPS);
        assertEquals(4f, rootX(second), EPS);
    }

    @Test
    void rebindingToTheSameModelPreservesTheSinksChannelResetState() {
        ModelInstance model = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(model);
        target.publish(publication(1, null, poseX(8f), Continuity.SNAP));
        target.sample(context("frame-3", 1.0));

        target.rebind(model);
        target.publish(new TargetPublication<>(
                AnimationTargetRef.modelPose(), 2, new OutputState.Cleared<>("detached")));
        target.sample(context("frame-4", 1.0));

        assertNull(rootTransform(model));
    }

    @Test
    void clearedPublicationResetsChannelsPreviouslyWrittenByTheTarget() {
        ModelInstance model = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(model);
        target.publish(publication(1, null, poseX(8f), Continuity.SNAP));
        target.sample(context("frame-5", 1.0));
        assertEquals(8f, rootX(model), EPS);

        target.publish(new TargetPublication<>(
                AnimationTargetRef.modelPose(), 2, new OutputState.Cleared<>("detached")));
        target.sample(context("frame-6", 1.0));

        assertNull(rootTransform(model));
    }

    @Test
    void closeIsIdempotentAndStopsWritingAfterReferencesAreReleased() {
        ModelInstance model = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(model);
        target.publish(publication(1, null, poseX(8f), Continuity.SNAP));

        assertDoesNotThrow(() -> {
            target.close();
            target.close();
            target.sample(context("frame-7", 1.0));
        });

        assertNull(rootTransform(model));
    }

    @Test
    void closeClearsACommittedPoseBeforeReleasingTheModel() {
        ModelInstance model = minimalModel();
        ModelPoseTarget target = new ModelPoseTarget(model);
        target.publish(publication(1, null, poseX(8f), Continuity.SNAP));
        target.sample(context("frame-before-close", 1.0));
        assertEquals(8f, rootX(model), EPS);

        target.close();

        assertNull(target.model());
        assertNull(rootTransform(model));
    }

    private static TargetPublication<PoseOutput> publication(
            long revision, Pose previous, Pose current, Continuity continuity) {
        return new TargetPublication<>(
                AnimationTargetRef.modelPose(),
                revision,
                new OutputState.Active<>(
                        previous == null ? null : new PoseOutput(previous),
                        new PoseOutput(current),
                        continuity));
    }

    private static Pose poseX(float x) {
        return Pose.bone("root", new Transform().translate(x, 0f, 0f));
    }

    private static RenderSampleContext context(String frame, double alpha) {
        return new RenderSampleContext(
                new RenderFrameToken(frame), alpha, FrameInputSnapshot.empty());
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
