package lib.kasuga.rendering.models.uml.dynamic.physics;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.CollisionEnvironment;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.DistanceJoint;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RayHit;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ActiveRagdollModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.PmxJoint;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.PmxRigidBody;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneFlags;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxGlobalInfo;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("box3d")
class SkeletonRagdollTest {
    @Test
    void sharedSceneLetsDifferentModelsCollideAtALargeWorldOrigin() {
        Vector3d sceneOrigin = new Vector3d(30_000_000.375, 96.125, -29_999_999.625);
        ModelInstance leftInstance = freeBodyInstance(0f);
        ModelInstance rightInstance = freeBodyInstance(0f);
        leftInstance.getSkeletonInstance().enableFloatingOrigin(
                new Vector3d(sceneOrigin).add(-1.5, 0.0, 0.0));
        rightInstance.getSkeletonInstance().enableFloatingOrigin(
                new Vector3d(sceneOrigin).add(1.5, 0.0, 0.0));

        try (ModelPhysicsScene scene = new ModelPhysicsScene(sceneOrigin, 4)) {
            SkeletonRagdoll left = scene.attach(leftInstance);
            SkeletonRagdoll right = scene.attach(rightInstance);
            SkeletonRagdoll.Body leftBody = left.bodies().getFirst();
            SkeletonRagdoll.Body rightBody = right.bodies().getFirst();
            scene.world().setGravity(new Vector3f());
            left.setSelfCollisionsEnabled(false);
            right.setSelfCollisionsEnabled(false);
            leftBody.setLinearVelocity(new Vector3f(2f, 0f, 0f));
            rightBody.setLinearVelocity(new Vector3f(-2f, 0f, 0f));

            assertTrue(leftBody.selfCollisionGroup() != rightBody.selfCollisionGroup(),
                    "each model needs a distinct no-self-contact group");
            for (int step = 0; step < 240; step++) scene.step(1f / 120f);

            assertTrue(leftBody.position().x < rightBody.position().x,
                    "bodies from separate models must not tunnel through each other");
            assertTrue(leftBody.position().distance(rightBody.position()) > 1.8f,
                    "cross-model contact must keep the two radius-one bodies separated");
            assertEquals(sceneOrigin.x + leftBody.position().x,
                    left.worldPosition(leftBody).x, 1e-8);
        }
    }

    @Test
    void sharedSceneBindsAJointAcrossTwoModels() {
        Vector3d origin = new Vector3d(1_000_000.25, 80.0, -1_000_000.25);
        ModelInstance firstInstance = freeBodyInstance(0f);
        ModelInstance secondInstance = freeBodyInstance(0f);
        firstInstance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(origin).add(-3.0, 0.0, 0.0));
        secondInstance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(origin).add(3.0, 0.0, 0.0));

        try (ModelPhysicsScene scene = new ModelPhysicsScene(origin, 4)) {
            SkeletonRagdoll first = scene.attach(firstInstance);
            SkeletonRagdoll second = scene.attach(secondInstance);
            SkeletonRagdoll.Body a = first.bodies().getFirst();
            SkeletonRagdoll.Body b = second.bodies().getFirst();
            scene.world().setGravity(new Vector3f());
            DistanceJoint tether = DistanceJoint.between(a, b)
                    .length(2f)
                    .spring(true, 5f, 0.9f)
                    .build();
            scene.addJoint(tether);

            for (int step = 0; step < 360; step++) scene.step(1f / 120f);

            assertTrue(tether.isBound());
            assertEquals(2f, a.position().distance(b.position()), 0.15f,
                    "one native joint must constrain bodies owned by different models");
        }
    }

    @Test
    void disabledSharedRagdollLeavesTheNativeSimulationUntilReenabled() {
        ModelInstance instance = freeBodyInstance(0f);
        try (ModelPhysicsScene scene = new ModelPhysicsScene(new Vector3d(), 4)) {
            SkeletonRagdoll ragdoll = scene.attach(instance);
            SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();
            scene.world().setGravity(new Vector3f());
            body.setLinearVelocity(new Vector3f(5f, 0f, 0f));

            instance.disablePhysics();
            Vector3f disabledPosition = body.position();
            assertFalse(scene.world().bodyEnabled(body));
            for (int step = 0; step < 60; step++) scene.step(1f / 60f);
            assertEquals(disabledPosition, body.position(),
                    "disabled shared bodies must neither integrate nor collide");

            instance.enablePhysics(scene, null);
            assertTrue(scene.world().bodyEnabled(body));
        }
    }

    @Test
    void keepsLargeWorldCoordinatesOutOfBoneAndBox3dFloats() {
        Vector3d origin = new Vector3d(30_000_000.375, 96.125, -29_999_999.625);
        ModelInstance instance = freeBodyInstance(0f);
        instance.getSkeletonInstance().enableFloatingOrigin(origin);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();

        assertEquals(origin.x, ragdoll.worldOrigin().x, 0.0);
        assertEquals(origin.z, ragdoll.worldOrigin().z, 0.0);
        assertEquals(0f, body.position().length(), 1e-7f,
                "native physics positions must remain origin-local");
        assertEquals(0f, instance.getSkeletonInstance().getAbsoluteTransforms()
                .get(body.bone()).getPosition().length(), 1e-7f,
                "bone matrices must not contain the large world anchor");
        var worldBox = ragdoll.addStaticBoxColliderWorld(
                origin.x - 1.0, origin.y - 2.0, origin.z - 1.0,
                origin.x + 1.0, origin.y - 1.0, origin.z + 1.0, 0.8f, 0f);
        assertEquals(new Vector3f(-1f, -2f, -1f), worldBox.minimum());
        assertEquals(new Vector3f(1f, -1f, 1f), worldBox.maximum());
        ragdoll.removeStaticBoxCollider(worldBox);

        RayHit hit = ragdoll.raycastWorld(origin.x, origin.y, origin.z - 3.0,
                new Vector3f(0f, 0f, 1f), 10f).orElseThrow();
        assertEquals(body, hit.body());
        assertTrue(ragdoll.beginDrag(hit));
        Vector3f localTarget = ragdoll.worldToSimulation(
                origin.x + 0.25, origin.y, origin.z - 1.0);
        assertEquals(new Vector3f(0.25f, 0f, -1f), localTarget,
                "double subtraction must retain sub-block targets at the world border");
        for (int step = 0; step < 120; step++) {
            ragdoll.updateDragTargetWorld(origin.x + 0.25, origin.y,
                    origin.z - 1.0, 1f / 120f);
            ragdoll.step(1f / 120f);
        }
        assertTrue(body.position().isFinite());

        Vector3d worldPosition = ragdoll.worldPosition(body);
        assertEquals(body.position().x, worldPosition.x - origin.x, 1e-8);
        assertEquals(body.position().y, worldPosition.y - origin.y, 1e-8);
        assertEquals(body.position().z, worldPosition.z - origin.z, 1e-8);
    }

    @Test
    void ordersTickLoopModulesAroundTheIkAndPhysicsSlotsDeterministically() {
        ModelInstance instance = freeBodyInstance(0f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        List<String> executed = new ArrayList<>();
        ModelTickLoop loop = instance.getTickLoop();
        loop.addPreIk("trace-pre", trace(executed, "pre"));
        loop.addPostIk("trace-post-ik", trace(executed, "post-ik"));
        loop.addPostPhysics("trace-post-physics", trace(executed, "post-physics"));

        assertEquals(List.of(ModelTickLoop.SLOT_APPLY, "trace-pre",
                ModelTickLoop.SLOT_IK, "trace-post-ik",
                ModelTickLoop.SLOT_PHYSICS, "trace-post-physics",
                ModelTickLoop.SLOT_ANCHOR), loop.getPipeline().ids());

        instance.simulatePhysics(1f / 120f);

        assertEquals(List.of("pre", "post-ik", "post-physics"), executed,
                "user modules must run in their mounted stage around IK and physics");
    }

    @Test
    void closingAnInstanceReleasesItsCollisionEnvironment() {
        class ClosingEnvironment implements CollisionEnvironment, AutoCloseable {
            private boolean closed;
            @Override public void update(RigidBodyWorld world) {}
            @Override public void close() { closed = true; }
        }
        ModelInstance instance = freeBodyInstance(0f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ClosingEnvironment environment = new ClosingEnvironment();
        ragdoll.setCollisionEnvironment(environment);

        instance.close();

        assertTrue(environment.closed);
        assertFalse(ragdoll.enabled());
        assertNull(instance.getRagdoll());
    }

    @Test
    void defaultGravityUsesWorldUnitsInsteadOfModelScale() {
        SkeletonRagdoll ragdoll = instance(new Vector3f(1f / 12f)).enablePhysics();

        assertEquals(-9.80665f, ragdoll.gravity().y, 1e-6f,
                "PMX model scaling must not weaken world acceleration");
    }

    @Test
    void dampingMatchesBox3dRateInsteadOfSixtyTimesThatRate() {
        ModelInstance instance = freeBodyInstance(2f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();
        body.setLinearVelocity(new Vector3f(12f, 0f, 0f));

        float dt = 1f / 120f;
        ragdoll.step(dt);

        float substep = dt / ragdoll.substepCount();
        float expected = 12f / (float) Math.pow(1f + 2f * substep, ragdoll.substepCount());
        assertEquals(expected, body.linearVelocity().x, 1e-4f);
    }

    @Test
    void accumulatesRenderDeltasIntoFixedWorldSteps() {
        ModelInstance instance = freeBodyInstance(0f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();
        body.setLinearVelocity(new Vector3f(1f, 0f, 0f));

        ragdoll.step(1f / 240f);
        assertEquals(0f, body.position().x, 1e-7f,
                "a partial frame must not become a variable physics step");

        ragdoll.step(1f / 240f);
        assertEquals(1f / 120f, body.position().x, 1e-6f,
                "accumulated frame time must advance one fixed 120 Hz world step");
        assertEquals(4, ragdoll.substepCount());
    }

    @Test
    void boundsPerUpdateCatchUpWorkAndReportsDiscardedTime() {
        SkeletonRagdoll ragdoll = freeBodyInstance(0f).enablePhysics();
        ragdoll.setGravity(new Vector3f());
        ragdoll.setMaxFixedStepsPerUpdate(3);
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();
        body.setLinearVelocity(new Vector3f(1f, 0f, 0f));

        ragdoll.step(10f / 120f);

        assertEquals(3, ragdoll.lastFixedStepCount());
        assertEquals(3f / 120f, body.position().x, 1e-6f);
        assertEquals(7f / 120f, ragdoll.droppedSimulationTime(), 1e-6f);
        ragdoll.step(1f / 120f);
        assertEquals(1, ragdoll.lastFixedStepCount(),
                "discarded whole steps must not remain as a permanent catch-up backlog");
    }

    @Test
    void exposesBodyLookupAndWorldSpaceImpulseControls() {
        SkeletonRagdoll ragdoll = freeBodyInstance(0f).enablePhysics();
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();

        assertEquals(body, ragdoll.body(0).orElseThrow());
        assertEquals(body, ragdoll.body("root").orElseThrow());
        assertEquals(body, ragdoll.body(body.bone()).orElseThrow());
        assertTrue(ragdoll.applyImpulse(body, new Vector3f(2f, 0f, 0f)));
        assertEquals(2f, body.linearVelocity().x, 1e-6f);

        assertTrue(ragdoll.applyImpulse(body, new Vector3f(1f, 0f, 0f),
                new Vector3f(0f, 1f, 0f)));
        assertTrue(body.angularVelocity().z < 0f,
                "an off-center impulse must produce angular velocity");
        assertTrue(ragdoll.applyAngularImpulse(body, new Vector3f(0f, 1f, 0f)));
        assertTrue(body.angularVelocity().y > 0f);
    }

    @Test
    void exposesNativeContinuousForceTorqueAndGravityScaleControls() {
        SkeletonRagdoll ragdoll = freeBodyInstance(0f).enablePhysics();
        ragdoll.setGravity(new Vector3f());
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();

        assertTrue(ragdoll.setGravityScale(body, 0.25f));
        assertEquals(0.25f, ragdoll.gravityScale(body), 1e-6f);
        assertTrue(ragdoll.applyForce(body, new Vector3f(12f, 0f, 0f)));
        assertTrue(ragdoll.applyTorque(body, new Vector3f(0f, 4f, 0f)));
        ragdoll.step(1f / 120f);

        assertTrue(body.linearVelocity().x > 0f);
        assertTrue(body.angularVelocity().y > 0f);
    }

    @Test
    void activeRagdollModuleDrivesBox3dTowardThePostIkAnimationPose() {
        ModelInstance instance = freeBodyInstance(0f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();
        instance.getTickLoop().addPostIk("active",
                new ActiveRagdollModule(4f, 1f, 1000f, 1000f));
        instance.getSkeletonInstance().offset("root", new Vector3f(1f, 0f, 0f));

        instance.simulatePhysics(1f / 120f);

        assertTrue(body.linearVelocity().x > 0f,
                "the post-IK module must enqueue a native Box3D force toward the animated body target");
    }

    @Test
    void disabledSleepingNeverFreezesAQuietSupportedIsland() {
        SkeletonRagdoll ragdoll = freeBodyInstance(0f).enablePhysics();
        ragdoll.setGravity(new Vector3f());
        ragdoll.addGroundPlane(0f, 0.8f, 0f);
        ragdoll.setSleepingEnabled(false);

        for (int step = 0; step < 240; step++) ragdoll.step(1f / 120f);

        assertFalse(ragdoll.sleeping());
        assertFalse(ragdoll.sleepingEnabled());
    }

    @Test
    void enforcesTranslationAndRotationLimitsAsHardConstraints() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        ragdoll.setSolverIterations(16);

        SkeletonRagdoll.Body dynamic = ragdoll.bodies().get(1);
        dynamic.teleport(new Vector3f(2f, -1f, 0.8f),
                new Quaternionf().rotationXYZ(0.9f, -0.7f, 0.6f));
        for (int step = 0; step < 120; step++) ragdoll.step(1f / 120f);

        Vector3f position = ragdoll.joints().getFirst().relativePosition();
        Vector3f rotation = ragdoll.joints().getFirst().relativeRotation();
        assertTrue(position.length() <= 0.02f, "Box3D spherical joint anchors must converge");
        assertTrue(rotation.isFinite());
    }

    @Test
    void scalesBodiesAndLinearJointLimitsWithTheLoadedModel() {
        ModelInstance instance = instance(new Vector3f(0.5f));
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        assertEquals(new Vector3f(0.5f), ragdoll.bodies().getFirst().shapeSize());

        ragdoll.bodies().get(1).teleport(new Vector3f(1f, 0f, 0f), new Quaternionf());
        for (int step = 0; step < 120; step++) ragdoll.step(1f / 120f);
        assertTrue(ragdoll.joints().getFirst().relativePosition().length() <= 0.02f,
                "Box3D spherical joint anchors must converge");
    }

    @Test
    void modeTwoSimulatesTheBodyButPreservesAnimatedBonePosition() {
        ModelInstance instance = instance(new Vector3f(1f), 2);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f(0f, -20f, 0f));
        SkeletonRagdoll.Body body = ragdoll.bodies().get(1);
        body.setLinearVelocity(new Vector3f(0f, 1f, 0f));
        body.setAngularVelocity(new Vector3f(0f, 0f, 2f));
        ragdoll.step(1f / 60f);

        assertTrue(body.position().isFinite());
        assertTrue(Math.abs(body.rotation().z) > 1e-4f);
        Bone child = instance.getModel().getSkeleton().getBoneMap().get("child");
        assertEquals(new Vector3f(),
                instance.getSkeletonInstance().getAbsoluteTransforms().get(child).getPosition());
    }

    @Test
    void rotationOnlyBodyPreservesBoneLengthWhenItsPhysicalParentMoves() {
        Bone root = bone("root", -1, new Vector3f());
        Bone child = bone("child", 0, new Vector3f(0f, 1f, 0f));
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());

        PmxRigidBody rootBody = body("root-body", 0, 1, new Vector3f());
        PmxRigidBody childBody = body("child-body", 1, 2, new Vector3f(0f, 1f, 0f));
        PmxJoint joint = new PmxJoint("joint", "joint", 0, 0, 1,
                new Vector3f(0f, 1f, 0f), new Vector3f(),
                new Vector3f(), new Vector3f(), new Vector3f(-0.2f), new Vector3f(0.2f),
                new Vector3f(), new Vector3f());
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(rootBody, childBody),
                List.of(joint), List.of());
        MmdModelData data = new MmdModelData(header(), tail, new Vector3f(1f));
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
            body.teleport(body.position().add(0f, 3f, 0f), body.rotation());
        }
        ragdoll.step(1f / 120f);

        Vector3f rootPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(root).getPosition();
        Vector3f childPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(child).getPosition();
        assertEquals(1f, rootPosition.distance(childPosition), 1e-5f,
                "rotation-only physics must not counter-translate a child away from its parent");
        assertEquals(4f, childPosition.y, 1e-5f,
                "the child must inherit the physical motion of its parent");
    }

    @Test
    void dynamicBodyMotionIsWrittenBackToItsBone() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f(0f, -20f, 0f));
        Bone child = instance.getModel().getSkeleton().getBoneMap().get("child");
        float initialY = instance.getSkeletonInstance().getAbsoluteTransforms().get(child).getPosition().y;

        ragdoll.step(1f / 20f);

        float simulatedY = instance.getSkeletonInstance().getAbsoluteTransforms().get(child).getPosition().y;
        assertTrue(simulatedY < initialY - 1e-4f,
                "mode-1 body motion must reach the rendered skeleton pose");
    }

    @Test
    void completedPhysicsPoseIsExposedForUploadWithoutSecondHierarchyEvaluation() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f(0f, -20f, 0f));

        ragdoll.step(1f / 20f);

        long physicsVersion = instance.getSkeletonInstance().getVersion();
        assertTrue(instance.checkForUpdate(),
                "a pre-evaluated physics pose must still wake the render backend");
        instance.update();
        assertEquals(physicsVersion, instance.getSkeletonInstance().getVersion(),
                "render upload must reuse the physics hierarchy instead of solving IK again");
        assertFalse(instance.checkForUpdate(), "the uploaded skeleton version must be acknowledged");
    }

    @Test
    void explicitProfilePromotesPrimaryBodiesAndBuildsRegisteredTopology() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll.Profile profile = SkeletonRagdoll.Profile.of(
                new SkeletonRagdoll.Registration(0, SkeletonRagdoll.BodyRole.PELVIS),
                new SkeletonRagdoll.Registration(1, 0, SkeletonRagdoll.BodyRole.SPINE));

        SkeletonRagdoll ragdoll = instance.enablePhysics(profile);

        assertEquals(2, ragdoll.bodies().size());
        assertEquals(1, ragdoll.joints().size());
        assertEquals(1, ragdoll.bodies().getFirst().source().mode(),
                "registered PMX mode-0 collision anchors become dynamic primary bodies");
        assertFalse(ragdoll.selfCollisionsEnabled(),
                "one configured humanoid uses Box3D-style negative-group self filtering");
        assertEquals(ragdoll.bodies().getFirst(), ragdoll.joints().getFirst().bodyA());
        assertEquals(ragdoll.bodies().get(1), ragdoll.joints().getFirst().bodyB());
    }

    @Test
    void pmxProfileCanRetainAuthoredSecondaryBodyChains() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll.Profile profile = SkeletonRagdoll.Profile.of(
                new SkeletonRagdoll.Registration(0, SkeletonRagdoll.BodyRole.PELVIS))
                .withSecondaryBodies();

        SkeletonRagdoll ragdoll = instance.enablePhysics(profile);

        assertTrue(ragdoll.profile().includeSecondaryBodies());
        assertEquals(2, ragdoll.bodies().size(),
                "the unregistered authored child must remain as secondary motion");
        assertEquals(1, ragdoll.joints().size(),
                "an authored primary-to-secondary joint must be retained");
        assertTrue(((SkeletonRagdoll.Body) ragdoll.joints().getFirst().bodyA()).secondaryAnchorBody(),
                "secondary chains must use a one-way kinematic primary anchor");
        assertEquals(ragdoll.body(1).orElseThrow(), ragdoll.joints().getFirst().bodyB());
        assertEquals(new Vector3f(1f), ragdoll.body(1).orElseThrow().shapeSize(),
                "secondary motion must preserve the author's PMX shape");
    }

    @Test
    void profileFreeFallIsNotSlowedByStabilizationDamping() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll ragdoll = instance.enablePhysics(SkeletonRagdoll.Profile.of(
                new SkeletonRagdoll.Registration(0, SkeletonRagdoll.BodyRole.PELVIS),
                new SkeletonRagdoll.Registration(1, 0, SkeletonRagdoll.BodyRole.SPINE)));
        ragdoll.setCollisionsEnabled(false);
        ragdoll.setGravity(new Vector3f(0f, -9.80665f, 0f));
        float startY = ragdoll.bodies().getFirst().position().y;

        int steps = 120;
        float dt = 1f / 120f;
        for (int i = 0; i < steps; i++) ragdoll.step(dt);

        float expectedVelocity = -9.80665f * steps * dt;
        int substeps = RigidBodyWorld.PROFILE_SUBSTEP_COUNT;
        int integrationSteps = steps * substeps;
        float integrationDt = dt / substeps;
        float expectedDisplacement = -9.80665f * integrationDt * integrationDt
                * integrationSteps * (integrationSteps + 1) * 0.5f;
        assertEquals(expectedVelocity, ragdoll.bodies().getFirst().linearVelocity().y, 0.002f,
                "joint stabilization must not damp whole-island falling velocity");
        assertEquals(startY + expectedDisplacement, ragdoll.bodies().getFirst().position().y, 0.002f,
                "profile free fall must follow Box3D's substepped gravity integration");
    }

    @Test
    void profileUsesSwingTwistLimitsWithoutEulerFlipping() {
        ModelInstance instance = instance(new Vector3f(1f));
        SkeletonRagdoll ragdoll = instance.enablePhysics(SkeletonRagdoll.Profile.of(
                new SkeletonRagdoll.Registration(0, SkeletonRagdoll.BodyRole.PELVIS),
                new SkeletonRagdoll.Registration(1, 0, SkeletonRagdoll.BodyRole.SPINE)));
        ragdoll.setGravity(new Vector3f());
        ragdoll.setCollisionsEnabled(false);
        ragdoll.setSolverIterations(24);
        SkeletonRagdoll.Body child = ragdoll.bodies().get(1);
        child.teleport(child.position(), new Quaternionf(child.rotation())
                .rotateX(2.2f).rotateY(1.4f));
        float initialViolation = ragdoll.joints().getFirst().angularLimitViolation();
        assertTrue(initialViolation > 0.1f);

        for (int i = 0; i < 120; i++) ragdoll.step(1f / 120f);

        assertTrue(ragdoll.joints().getFirst().angularLimitViolation() < initialViolation,
                "Box3D cone/twist limit must reduce the authored violation");
    }

    @Test
    void registeredBranchesMoveThroughSkeletonRootWithoutStretchingInternalAncestor() {
        Bone center = bone("center", -1, new Vector3f());
        Bone waist = bone("waist", 0, new Vector3f(0f, 1f, 0f));
        Bone lower = bone("lower", 1, new Vector3f(-1f, 1f, 0f));
        Bone upper = bone("upper", 1, new Vector3f(1f, 1f, 0f));
        lower.setTransform(new Transform().translate(-1f, 0f, 0f));
        upper.setTransform(new Transform().translate(1f, 0f, 0f));
        center.setChildren(new Bone[]{waist});
        waist.setParent(center);
        waist.setChildren(new Bone[]{lower, upper});
        lower.setParent(waist);
        upper.setParent(waist);
        Bone[] bones = {center, waist, lower, upper};
        Skeleton skeleton = new Skeleton(bones, center, new Anchor[0], null, new Transform());

        PmxRigidBody pelvisBody = body("pelvis", 2, 0, new Vector3f(-1f, 1f, 0f));
        PmxRigidBody spineBody = body("spine", 3, 0, new Vector3f(1f, 1f, 0f));
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(pelvisBody, spineBody),
                List.of(), List.of());
        MmdModelData data = new MmdModelData(header(), tail, new Vector3f(1f));
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        SkeletonRagdoll.Profile profile = SkeletonRagdoll.Profile.of(
                new SkeletonRagdoll.Registration(0, SkeletonRagdoll.BodyRole.PELVIS),
                new SkeletonRagdoll.Registration(1, 0, SkeletonRagdoll.BodyRole.SPINE));
        SkeletonRagdoll ragdoll = instance.enablePhysics(profile);
        ragdoll.setGravity(new Vector3f());
        for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
            body.teleport(body.position().add(0f, 3f, 0f), body.rotation());
        }

        ragdoll.step(1f / 120f);

        Vector3f centerPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(center).getPosition();
        Vector3f waistPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(waist).getPosition();
        Vector3f lowerPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(lower).getPosition();
        Vector3f upperPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(upper).getPosition();
        assertEquals(3f, centerPosition.y, 1e-5f,
                "global ragdoll motion must be applied at the skeleton root");
        assertEquals(1f, centerPosition.distance(waistPosition), 1e-5f,
                "moving an internal common ancestor would stretch it away from the skeleton root");
        assertEquals(1f, waistPosition.distance(lowerPosition), 1e-5f);
        assertEquals(1f, waistPosition.distance(upperPosition), 1e-5f);
    }

    @Test
    void registeredHelperBoneRigidlyFollowsPhysicalAncestorWithoutGrantStretching() {
        Bone root = bone("root", -1, new Vector3f());
        boolean[] firstFlags = new boolean[6];
        boolean[] secondFlags = new boolean[6];
        secondFlags[1] = true;
        PmxBone helperData = new PmxBone("helper", "helper", new Vector3f(0f, 1f, 0f), 0, 0,
                new PmxBoneFlags(firstFlags, secondFlags), new Vector3f(),
                new lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.ParentBoneInherit(0, 1f),
                null, null, -1, null);
        Bone helper = new Bone("helper", new Transform().translate(0f, 1f, 0f), helperData);
        Bone child = bone("child", 1, new Vector3f(0f, 2f, 0f));
        // The PMX loader stores a bind-local translation, while PmxBone.position
        // remains model-space.
        child.setTransform(new Transform().translate(0f, 1f, 0f));
        root.setChildren(new Bone[]{helper});
        helper.setParent(root);
        helper.setChildren(new Bone[]{child});
        child.setParent(helper);
        Bone[] bones = {root, helper, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());

        PmxRigidBody rootBody = body("root-body", 0, 1, new Vector3f());
        PmxRigidBody childBody = body("child-body", 2, 1, new Vector3f(0f, 2f, 0f));
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(rootBody, childBody),
                List.of(), List.of());
        MmdModelData data = new MmdModelData(header(), tail, new Vector3f(1f));
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        SkeletonRagdoll ragdoll = instance.enablePhysics(SkeletonRagdoll.Profile.of(
                new SkeletonRagdoll.Registration(0, SkeletonRagdoll.BodyRole.PELVIS),
                new SkeletonRagdoll.Registration(1, 0, SkeletonRagdoll.BodyRole.SPINE)));
        ragdoll.setGravity(new Vector3f());
        for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
            body.teleport(body.position().add(0f, 3f, 0f), body.rotation());
        }
        ragdoll.bodies().getFirst().setAngularVelocity(new Vector3f(0.6f, -0.4f, 1.8f));
        ragdoll.bodies().get(1).setAngularVelocity(new Vector3f(-0.5f, 0.8f, -2.1f));

        for (int i = 0; i < 120; i++) ragdoll.step(1f / 120f);

        Vector3f rootPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(root).getPosition();
        Vector3f helperPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(helper).getPosition();
        Vector3f childPosition = instance.getSkeletonInstance().getAbsoluteTransforms().get(child).getPosition();
        assertEquals(1f, rootPosition.distance(helperPosition), 1e-4f,
                "grant translation must not be evaluated again over the physical pose");
        assertEquals(1f, helperPosition.distance(childPosition), 0.01f,
                "the body anchor and rendered helper chain must meet at the same joint");
    }

    @Test
    void raycastDragsThePickedLocalPointWithoutTeleportingTheBody() {
        ModelInstance instance = freeBodyInstance(0f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        ragdoll.setCollisionsEnabled(false);

        RayHit hit = ragdoll.raycast(
                new Vector3f(0f, 0f, -3f), new Vector3f(0f, 0f, 1f), 10f).orElseThrow();
        assertEquals(2f, hit.distance(), 1e-5f);
        assertTrue(ragdoll.beginDrag(hit));
        ragdoll.updateDragTarget(new Vector3f(1f, 0f, -1f), 1f / 60f);

        for (int step = 0; step < 60; step++) ragdoll.step(1f / 120f);

        Vector3f pickedPoint = ((SkeletonRagdoll.Body) hit.body()).toWorldPoint(new Vector3f(0f, 0f, -1f));
        assertTrue(pickedPoint.x > 0.8f, "the picked surface point must follow the drag target");
        assertEquals(-1f, pickedPoint.z, 0.2f);
        assertTrue(ragdoll.dragging());
        ragdoll.endDrag();
        assertFalse(ragdoll.dragging());
    }

    @Test
    void activeDragConstraintNeverFreezesTheSimulatedIsland() {
        ModelInstance instance = freeBodyInstance(0f);
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        ragdoll.setGravity(new Vector3f());
        ragdoll.setCollisionsEnabled(false);
        SkeletonRagdoll.Body body = ragdoll.bodies().getFirst();
        Vector3f target = body.position();

        assertTrue(ragdoll.beginDrag(body, target));
        for (int step = 0; step < 240; step++) {
            ragdoll.updateDragTarget(target, 1f / 120f);
            ragdoll.step(1f / 120f);
        }

        assertFalse(ragdoll.sleeping(),
                "an active mouse constraint must keep gravity, contacts and joints running");

        // Moving in increments smaller than the positional wake dead zone used
        // to leave an already-frozen island asleep forever.
        for (int step = 0; step < 120; step++) {
            target.add(0.001f, 0f, 0f);
            ragdoll.updateDragTarget(target, 1f / 120f);
            ragdoll.step(1f / 120f);
            assertFalse(ragdoll.sleeping());
        }
        assertTrue(body.position().x > 0.05f,
                "continuous sub-dead-zone target motion must still move the dragged body");
    }

    @Test
    void stationaryDragDoesNotDampUnrelatedBodies() {
        SkeletonRagdoll ragdoll = twoFreeBodyInstance().enablePhysics();
        ragdoll.setGravity(new Vector3f());
        ragdoll.setCollisionsEnabled(false);
        SkeletonRagdoll.Body held = ragdoll.bodies().get(0);
        SkeletonRagdoll.Body unrelated = ragdoll.bodies().get(1);
        unrelated.setLinearVelocity(new Vector3f(1f, 0f, 0f));
        unrelated.setAngularVelocity(new Vector3f(0f, 1f, 0f));
        assertTrue(ragdoll.beginDrag(held, held.position()));

        ragdoll.step(1f / 120f);

        assertEquals(1f, unrelated.linearVelocity().x, 1e-4f,
                "a stationary mouse target must not add whole-island linear damping");
        assertEquals(1f, unrelated.angularVelocity().y, 1e-4f,
                "a stationary mouse target must not add whole-island angular damping");
    }

    private static void assertWithin(Vector3f actual, Vector3f min, Vector3f max, float epsilon) {
        assertTrue(actual.x >= min.x - epsilon && actual.x <= max.x + epsilon, actual.toString());
        assertTrue(actual.y >= min.y - epsilon && actual.y <= max.y + epsilon, actual.toString());
        assertTrue(actual.z >= min.z - epsilon && actual.z <= max.z + epsilon, actual.toString());
    }

    private static ModelTickLoopModule trace(List<String> executed, String label) {
        return new ModelTickLoopModule() {
            @Override public void tick(Model model, PendingTransform[] transforms,
                                       ModelTickLoop loop, float deltaTime) {
                executed.add(label);
            }
            @Override public void destroy(Model model) {}
        };
    }

    private static ModelInstance instance(Vector3f scale) {
        return instance(scale, 1);
    }

    private static ModelInstance instance(Vector3f scale, int dynamicMode) {
        Bone root = bone("root", -1);
        Bone child = bone("child", 0);
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());

        PmxRigidBody fixed = body("fixed", 0, 0);
        PmxRigidBody dynamic = body("dynamic", 1, dynamicMode);
        PmxJoint joint = new PmxJoint("joint", "joint", 0, 0, 1,
                new Vector3f(), new Vector3f(),
                new Vector3f(-0.25f), new Vector3f(0.25f),
                new Vector3f(-0.2f), new Vector3f(0.2f),
                new Vector3f(), new Vector3f());
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(fixed, dynamic),
                List.of(joint), List.of());
        MmdModelData data = new MmdModelData(header(), tail, scale);
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        return new ModelInstance(model, null, null, null, null, null);
    }

    private static ModelInstance freeBodyInstance(float linearDamping) {
        Bone root = bone("root", -1);
        Bone[] bones = {root};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        PmxRigidBody dynamic = new PmxRigidBody("dynamic", "dynamic", 0, 0, 0, 0,
                new Vector3f(1f), new Vector3f(), new Vector3f(), 1f,
                linearDamping, 0f, 0f, 0f, 1);
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(dynamic), List.of(), List.of());
        MmdModelData data = new MmdModelData(header(), tail, new Vector3f(1f));
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        return new ModelInstance(model, null, null, null, null, null);
    }

    private static ModelInstance twoFreeBodyInstance() {
        Bone root = bone("root", -1);
        Bone child = bone("child", 0, new Vector3f(5f, 0f, 0f));
        child.setTransform(new Transform().translate(5f, 0f, 0f));
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(
                body("first", 0, 1), body("second", 1, 1, new Vector3f(5f, 0f, 0f))),
                List.of(), List.of());
        MmdModelData data = new MmdModelData(header(), tail, new Vector3f(1f));
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        return new ModelInstance(model, null, null, null, null, null);
    }

    private static PmxRigidBody body(String name, int bone, int mode) {
        return body(name, bone, mode, new Vector3f());
    }

    private static PmxRigidBody body(String name, int bone, int mode, Vector3f position) {
        return new PmxRigidBody(name, name, bone, 0, 0, 0,
                new Vector3f(1f), position, new Vector3f(), 1f,
                0f, 0f, 0f, 0f, mode);
    }

    private static Bone bone(String name, int parent) {
        return bone(name, parent, new Vector3f());
    }

    private static Bone bone(String name, int parent, Vector3f position) {
        PmxBone data = new PmxBone(name, name, new Vector3f(position), parent, 0,
                new PmxBoneFlags(), new Vector3f(), null, null, null, -1, null);
        Vector3f localPosition = new Vector3f(position);
        return new Bone(name, new Transform().translate(localPosition), data);
    }

    private static PmxHeader header() {
        PmxGlobalInfo info = new PmxGlobalInfo(StandardCharsets.UTF_8, (byte) 0,
                (byte) 4, (byte) 4, (byte) 4, (byte) 4, (byte) 4, (byte) 4);
        return new PmxHeader("PMX ", 2.0f, (byte) 8, info, "", "", "", "");
    }
}
