package lib.kasuga.rendering.models.uml.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.DistanceJoint;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneFlags;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxGlobalInfo;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("box3d")
class ModelPhysicsClusterManagerTest {

    @Test
    void distantInstancesFormSeparateClustersWithLocalizedOrigins() {
        Vector3d originA = new Vector3d(30_000_000.0, 100.0, -30_000_000.0);
        Vector3d originB = new Vector3d(0.0, 100.0, 0.0);

        ModelInstance instanceA = freeBodyInstance();
        ModelInstance instanceB = freeBodyInstance();
        instanceA.getSkeletonInstance().enableFloatingOrigin(originA);
        instanceB.getSkeletonInstance().enableFloatingOrigin(originB);

        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(24.0)) {
            manager.attach(instanceA);
            manager.attach(instanceB);

            assertEquals(2, manager.clusterCount(), "distant models must be split into separate clusters");
            assertNotSame(manager.sceneOf(instanceA), manager.sceneOf(instanceB),
                    "each distant model must have its own ModelPhysicsScene");

            Vector3d sceneOriginA = manager.sceneOf(instanceA).worldOrigin();
            Vector3d sceneOriginB = manager.sceneOf(instanceB).worldOrigin();

            assertEquals(originA.x, sceneOriginA.x, 1e-4);
            assertEquals(originB.x, sceneOriginB.x, 1e-4);
        }
    }

    @Test
    void closeInstancesMergeIntoSingleClusterAndCollide() {
        Vector3d baseOrigin = new Vector3d(10_000_000.0, 64.0, 10_000_000.0);
        ModelInstance leftInstance = freeBodyInstance();
        ModelInstance rightInstance = freeBodyInstance();
        leftInstance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(-1.5, 0.0, 0.0));
        rightInstance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(1.5, 0.0, 0.0));

        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(24.0)) {
            SkeletonRagdoll left = manager.attach(leftInstance);
            SkeletonRagdoll right = manager.attach(rightInstance);

            assertEquals(1, manager.clusterCount(), "models within cluster radius must merge into 1 cluster");
            assertSame(manager.sceneOf(leftInstance), manager.sceneOf(rightInstance),
                    "both models must share the same ModelPhysicsScene");

            SkeletonRagdoll.Body leftBody = left.bodies().getFirst();
            SkeletonRagdoll.Body rightBody = right.bodies().getFirst();
            left.setGravity(new Vector3f());
            right.setGravity(new Vector3f());
            left.setSelfCollisionsEnabled(false);
            right.setSelfCollisionsEnabled(false);
            leftBody.setLinearVelocity(new Vector3f(2f, 0f, 0f));
            rightBody.setLinearVelocity(new Vector3f(-2f, 0f, 0f));

            for (int step = 0; step < 240; step++) {
                manager.step(1f / 120f);
            }

            assertTrue(leftBody.position().x < rightBody.position().x,
                    "colliding bodies from merged cluster must not tunnel through each other");
            assertTrue(leftBody.position().distance(rightBody.position()) > 1.8f,
                    "cross-model contact must keep the two radius-one bodies separated");
        }
    }

    @Test
    void highRelativeClosingVelocityTriggersPredictivePreemptiveMerge() {
        Vector3d baseOrigin = new Vector3d(0.0, 100.0, 0.0);
        ModelInstance instanceA = freeBodyInstance();
        ModelInstance instanceB = freeBodyInstance();
        instanceA.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(0.0, 0.0, 0.0));
        instanceB.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(60.0, 0.0, 0.0));

        // Base cluster radius is 20m, distance is 60m.
        // If lookahead is 0.5s and closing velocity is 100m/s:
        // effectiveMergeRadius = 20 + 100 * 0.5 = 70m > 60m -> should pre-emptively merge!
        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(20.0, 30.0, 8)) {
            manager.setVelocityLookaheadSeconds(0.5);
            SkeletonRagdoll ragdollA = manager.attach(instanceA);
            SkeletonRagdoll ragdollB = manager.attach(instanceB);
            assertEquals(2, manager.clusterCount(), "without high velocity -> 2 separate clusters");

            // Set high closing velocity: A flying at +60 m/s towards B, B flying at -40 m/s towards A
            ragdollA.bodies().getFirst().setLinearVelocity(new Vector3f(60f, 0f, 0f));
            ragdollB.bodies().getFirst().setLinearVelocity(new Vector3f(-40f, 0f, 0f));
            Vector3d worldPositionA = ragdollA.worldPosition(ragdollA.bodies().getFirst());
            Vector3d worldPositionB = ragdollB.worldPosition(ragdollB.bodies().getFirst());

            manager.recluster(1f / 60f);

            assertEquals(1, manager.clusterCount(),
                    "high closing velocity must trigger pre-emptive merge before distance drops below base radius");
            assertSame(manager.sceneOf(instanceA), manager.sceneOf(instanceB));
            assertEquals(worldPositionA, ragdollA.worldPosition(ragdollA.bodies().getFirst()),
                    "scene migration must preserve A's world-space body position");
            assertEquals(worldPositionB, ragdollB.worldPosition(ragdollB.bodies().getFirst()),
                    "scene migration must preserve B's world-space body position");
        }
    }

    @Test
    void boundaryChatteringPreventedByTemporalGracePeriod() {
        Vector3d baseOrigin = new Vector3d(500.0, 70.0, 500.0);
        ModelInstance instanceA = freeBodyInstance();
        ModelInstance instanceB = freeBodyInstance();
        instanceA.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(0.0, 0.0, 0.0));
        instanceB.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(10.0, 0.0, 0.0));

        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(20.0, 30.0, 8)) {
            manager.setSplitGraceSeconds(0.5); // 0.5s grace period
            manager.attach(instanceA);
            manager.attach(instanceB);
            assertEquals(1, manager.clusterCount(), "initially 10m apart -> 1 cluster");

            // Simulate boundary hovering / slight oscillation just past splitRadius (35m) for 0.2s (< 0.5s grace)
            instanceB.getSkeletonInstance().transformRoot(new Transform().translate(35.0f, 0.0f, 0.0f));
            for (int step = 0; step < 12; step++) { // 12 * (1/60s) = 0.2s
                manager.recluster(1f / 60f);
            }

            assertEquals(1, manager.clusterCount(),
                    "brief boundary crossing within splitGraceSeconds must not split (flapping prevented)");

            // Move back slightly within split threshold (28m) -> resets separation timer
            instanceB.getSkeletonInstance().transformRoot(new Transform().translate(28.0f, 0.0f, 0.0f));
            manager.recluster(1f / 60f);
            assertEquals(1, manager.clusterCount());

            // Move far away (50m) and maintain separation for > 0.5s
            instanceB.getSkeletonInstance().transformRoot(new Transform().translate(50.0f, 0.0f, 0.0f));
            for (int step = 0; step < 36; step++) { // 36 * (1/60s) = 0.6s > 0.5s
                manager.recluster(1f / 60f);
            }

            assertEquals(2, manager.clusterCount(),
                    "sustained separation beyond grace period must commit the split into separate clusters");
            assertNotSame(manager.sceneOf(instanceA), manager.sceneOf(instanceB));
        }
    }

    @Test
    void jointBondedInstancesStayInSameClusterRegardlessOfDistance() {
        Vector3d baseOrigin = new Vector3d(0.0, 100.0, 0.0);
        ModelInstance instanceA = freeBodyInstance();
        ModelInstance instanceB = freeBodyInstance();
        instanceA.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(0.0, 0.0, 0.0));
        instanceB.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(100.0, 0.0, 0.0));

        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(20.0, 30.0, 8)) {
            manager.setSplitGraceSeconds(0.0); // instant split on test verification
            SkeletonRagdoll ragdollA = manager.attach(instanceA);
            SkeletonRagdoll ragdollB = manager.attach(instanceB);
            assertEquals(2, manager.clusterCount(), "without joint and 100m apart -> 2 clusters");

            DistanceJoint tether = DistanceJoint.between(
                    ragdollA.bodies().getFirst(),
                    ragdollB.bodies().getFirst()
            ).length(50f).build();

            manager.addJoint(tether);

            assertEquals(1, manager.clusterCount(), "joint-bonded instances must be grouped into the same cluster");
            assertSame(manager.sceneOf(instanceA), manager.sceneOf(instanceB));
            assertTrue(tether.isBound());

            manager.removeJoint(tether);
            assertEquals(2, manager.clusterCount(), "removing joint when far apart must split into 2 clusters");
            assertNotSame(manager.sceneOf(instanceA), manager.sceneOf(instanceB));
        }
    }

    @Test
    void configurableRadiusDynamicallyAltersClusteringBehavior() {
        Vector3d baseOrigin = new Vector3d(100.0, 60.0, 100.0);
        ModelInstance instanceA = freeBodyInstance();
        ModelInstance instanceB = freeBodyInstance();
        instanceA.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin));
        instanceB.getSkeletonInstance().enableFloatingOrigin(new Vector3d(baseOrigin).add(15.0, 0.0, 0.0));

        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(10.0, 15.0, 8)) {
            manager.setSplitGraceSeconds(0.0); // instant split for direct threshold verification
            manager.attach(instanceA);
            manager.attach(instanceB);
            assertEquals(2, manager.clusterCount(), "distance 15m > radius 10m -> 2 clusters");

            manager.setClusterRadius(25.0);
            assertEquals(25.0, manager.clusterRadius());
            manager.recluster(0f);
            assertEquals(1, manager.clusterCount(), "distance 15m < new radius 25m -> merged into 1 cluster");
            Vector3d worldPositionA = instanceA.getRagdoll().worldPosition(
                    instanceA.getRagdoll().bodies().getFirst());
            Vector3d worldPositionB = instanceB.getRagdoll().worldPosition(
                    instanceB.getRagdoll().bodies().getFirst());

            manager.setRadii(8.0, 12.0);
            assertEquals(8.0, manager.clusterRadius());
            assertEquals(12.0, manager.splitRadius());
            manager.recluster(0f);
            assertEquals(2, manager.clusterCount(), "distance 15m > splitRadius 12m -> split into 2 clusters");
            assertEquals(worldPositionA, instanceA.getRagdoll().worldPosition(
                    instanceA.getRagdoll().bodies().getFirst()));
            assertEquals(worldPositionB, instanceB.getRagdoll().worldPosition(
                    instanceB.getRagdoll().bodies().getFirst()));
        }
    }

    @Test
    void detachingInstanceCleansUpClusterAndJoints() {
        ModelInstance instanceA = freeBodyInstance();
        ModelInstance instanceB = freeBodyInstance();

        try (ModelPhysicsClusterManager manager = new ModelPhysicsClusterManager(24.0)) {
            manager.attach(instanceA);
            manager.attach(instanceB);
            assertEquals(1, manager.clusterCount());
            assertEquals(2, manager.instanceCount());
            DistanceJoint tether = DistanceJoint.between(
                    instanceA.getRagdoll().bodies().getFirst(),
                    instanceB.getRagdoll().bodies().getFirst()).length(2f).build();
            manager.addJoint(tether);
            assertEquals(1, manager.registeredJointCount());
            assertTrue(tether.isBound());

            assertTrue(manager.detach(instanceA));
            assertEquals(1, manager.clusterCount());
            assertEquals(1, manager.instanceCount());
            assertNull(instanceA.getRagdoll(), "detached instance should have its ragdoll cleaned up");
            assertNotNull(instanceB.getRagdoll(), "remaining instance keeps its ragdoll");
            assertEquals(0, manager.registeredJointCount(),
                    "joints referencing detached bodies must leave the manager registry");
            assertFalse(tether.isBound());
        }
    }

    private static ModelInstance freeBodyInstance() {
        Bone root = bone("root", -1);
        Bone[] bones = {root};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        PmxTail.PmxRigidBody dynamic = new PmxTail.PmxRigidBody("dynamic", "dynamic", 0, 0, 0, 0,
                new Vector3f(1f), new Vector3f(), new Vector3f(), 1f,
                0f, 0f, 0f, 0f, 1);
        PmxTail tail = new PmxTail(List.of(), List.of(), List.of(dynamic), List.of(), List.of());
        MmdModelData data = new MmdModelData(header(), tail, new Vector3f(1f));
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, data, null);
        return new ModelInstance(model, null, null, null, null, null);
    }

    private static Bone bone(String name, int parent) {
        PmxBone data = new PmxBone(name, name, new Vector3f(), parent, 0,
                new PmxBoneFlags(), new Vector3f(), null, null, null, -1, null);
        return new Bone(name, new Transform(), data);
    }

    private static PmxHeader header() {
        PmxGlobalInfo info = new PmxGlobalInfo(StandardCharsets.UTF_8, (byte) 0,
                (byte) 4, (byte) 4, (byte) 4, (byte) 4, (byte) 4, (byte) 4);
        return new PmxHeader("PMX ", 2.0f, (byte) 8, info, "", "", "", "");
    }
}
